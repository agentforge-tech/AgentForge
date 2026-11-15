package cloud.changlu.agentforge.agent.governance;

import cloud.changlu.agentforge.agent.IInterruptibleAgent;
import cloud.changlu.agentforge.agent.ReActAgent;
import cloud.changlu.agentforge.agent.domain.AgentRequest;
import cloud.changlu.agentforge.agent.domain.AgentRunState;
import cloud.changlu.agentforge.agent.domain.AgentSettings;
import cloud.changlu.agentforge.agent.extend.memory.window.MessageWindowChatMemoryProvider;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.AgentSuspendInfo;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.ApprovalAction;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptBatchResumeCommand;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptRequest;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptResponse;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptType;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserAnswer;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserResponse;
import cloud.changlu.agentforge.agent.memory.ChatMemoryProvider;
import cloud.changlu.agentforge.agent.step.ChatResult;
import cloud.changlu.agentforge.agent.step.ChatResultState;
import cloud.changlu.agentforge.agent.support.ScriptedChatModel;
import cloud.changlu.agentforge.agent.support.ScriptedStreamingChatModel;
import cloud.changlu.agentforge.agent.support.WeatherTools;
import cloud.changlu.agentforge.model.chat.message.ChatMessage;
import cloud.changlu.agentforge.model.chat.message.ChatMessageType;
import cloud.changlu.agentforge.model.chat.response.ChatResponse;
import cloud.changlu.agentforge.model.tool.execution.ToolService;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 验证权限/AskUser 中断在非流式与流式入口都能批量恢复。 */
public class InterruptResumeAgentTest {

    private static final Object MEMORY_ID = "interrupt-session";

    @Test
    public void shouldSuspendAndResumeInterruptByNonStreaming() {
        ScriptedChatModel chatModel =
                new ScriptedChatModel()
                        .enqueueToolCall("call_1", "getWeather", "{\"city\":\"Hangzhou\"}")
                        .enqueueText("恢复后继续完成");
        ChatMemoryProvider memoryProvider = new MessageWindowChatMemoryProvider(100);
        PermissionInterruptModule module =
                PermissionInterruptModule.safeDefaults().ask("getWeather", ".*").build();
        ReActAgent agent = agent(chatModel, null, toolService(), memoryProvider, module);

        ChatResult suspended = agent.run(request("杭州天气怎么样？"));

        assertEquals(ChatResultState.SUSPENDED, suspended.getChatResultState());
        assertEquals(AgentRunState.WAITING_INTERRUPT, suspended.getRunState());
        assertNotNull(suspended.getSuspendInfo());
        assertEquals(1, suspended.getSuspendInfo().getInterrupts().size());
        assertEquals(1, chatModel.callCount());

        InterruptRequest interrupt = suspended.getSuspendInfo().getInterrupts().get(0);
        assertEquals(InterruptType.PERMISSION_APPROVAL, interrupt.getType());

        ChatResult resumed = agent.resumeInterrupt(approve(suspended.getSuspendInfo(), interrupt));

        assertEquals(ChatResultState.FINISHED, resumed.getChatResultState());
        assertEquals(AgentRunState.SUCCESS, resumed.getRunState());
        assertEquals("恢复后继续完成", resumed.getRes());
        assertEquals(2, chatModel.callCount());

        List<ChatMessage> messages = memoryProvider.get(MEMORY_ID).messages();
        ChatMessage toolResult = messages.get(messages.size() - 2);
        assertEquals(ChatMessageType.TOOL_EXECUTION_RESULT, toolResult.type());
        assertTrue(toolResult.text().contains("22 degrees Celsius"));
    }

    @Test
    public void shouldResumeAskUserWithAnswerByNonStreaming() {
        ScriptedChatModel chatModel =
                new ScriptedChatModel()
                        .enqueueToolCall("call_1", "ask_user", "{\"question\":\"是否继续？\"}")
                        .enqueueText("已按要求继续");
        ChatMemoryProvider memoryProvider = new MessageWindowChatMemoryProvider(100);
        PermissionInterruptModule module =
                PermissionInterruptModule.safeDefaults().enableAskUser().build();
        ReActAgent agent = agent(chatModel, null, toolService(), memoryProvider, module);

        ChatResult suspended = agent.run(request("需要用户确认"));

        assertEquals(
                InterruptType.ASK_USER,
                suspended.getSuspendInfo().getInterrupts().get(0).getType());

        InterruptRequest interrupt = suspended.getSuspendInfo().getInterrupts().get(0);
        InterruptBatchResumeCommand command =
                new InterruptBatchResumeCommand(
                        suspended.getSuspendInfo().getBatchId(),
                        Collections.singletonList(
                                InterruptResponse.askUser(
                                        interrupt.getInterruptId(),
                                        AskUserResponse.text("q1", "继续"))));

        ChatResult resumed = agent.resumeInterrupt(command);

        assertEquals(AgentRunState.SUCCESS, resumed.getRunState());
        assertEquals("已按要求继续", resumed.getRes());

        List<ChatMessage> messages = memoryProvider.get(MEMORY_ID).messages();
        ChatMessage toolResult = messages.get(messages.size() - 2);
        assertEquals(ChatMessageType.TOOL_EXECUTION_RESULT, toolResult.type());
        assertTrue(toolResult.text().contains("\"status\":\"answered\""));
        assertTrue(toolResult.text().contains("\"customValue\":\"继续\""));
    }

    @Test
    public void shouldResumeStructuredChoiceAndTextAnswers() {
        String arguments =
                "{\"reason\":\"发布前确认\",\"questions\":["
                        + "{\"id\":\"env\",\"title\":\"选择部署环境\",\"type\":\"single_choice\",\"required\":true,"
                        + "\"options\":[{\"id\":\"test\",\"label\":\"测试\"},{\"id\":\"prod\",\"label\":\"生产\"}]},"
                        + "{\"id\":\"modules\",\"title\":\"选择需要更新的模块\",\"type\":\"multiple_choice\",\"required\":true,"
                        + "\"minSelections\":1,\"maxSelections\":2,"
                        + "\"options\":[{\"id\":\"api\",\"label\":\"API\"},{\"id\":\"web\",\"label\":\"Web\"},{\"id\":\"job\",\"label\":\"Job\"}]},"
                        + "{\"id\":\"note\",\"title\":\"请输入发布说明\",\"type\":\"text\",\"required\":false,\"maxLength\":100}"
                        + "]}";
        ScriptedChatModel chatModel =
                new ScriptedChatModel()
                        .enqueueToolCall("call_1", "ask_user", arguments)
                        .enqueueText("已按用户选择继续");
        ChatMemoryProvider memoryProvider = new MessageWindowChatMemoryProvider(100);
        PermissionInterruptModule module =
                PermissionInterruptModule.safeDefaults().enableAskUser().build();
        ReActAgent agent = agent(chatModel, null, toolService(), memoryProvider, module);

        ChatResult suspended = agent.run(request("发布前需要确认"));

        InterruptRequest interrupt = suspended.getSuspendInfo().getInterrupts().get(0);
        assertEquals(3, interrupt.getAskUserRequest().getQuestions().size());

        InterruptBatchResumeCommand command =
                new InterruptBatchResumeCommand(
                        suspended.getSuspendInfo().getBatchId(),
                        Collections.singletonList(
                                InterruptResponse.askUser(
                                        interrupt.getInterruptId(),
                                        AskUserResponse.of(
                                                AskUserAnswer.single("env", "prod"),
                                                AskUserAnswer.multiple(
                                                        "modules", Arrays.asList("api", "web")),
                                                AskUserAnswer.text("note", "灰度发布")))));

        ChatResult resumed = agent.resumeInterrupt(command);

        assertEquals(AgentRunState.SUCCESS, resumed.getRunState());
        List<ChatMessage> messages = memoryProvider.get(MEMORY_ID).messages();
        ChatMessage toolResult = messages.get(messages.size() - 2);
        String text = toolResult.text();
        assertTrue(text.contains("\"status\":\"answered\""));
        assertTrue(text.contains("\"questionId\":\"env\""));
        assertTrue(text.contains("\"selectedOptionIds\":[\"prod\"]"));
        assertTrue(text.contains("\"questionId\":\"modules\""));
        assertTrue(text.contains("\"api\""));
        assertTrue(text.contains("\"customValue\":\"灰度发布\""));
    }

    @Test
    public void shouldSuspendAndResumeStructuredAskUserByStreaming() {
        String arguments =
                "{\"reason\":\"发布前确认\",\"questions\":["
                        + "{\"id\":\"env\",\"title\":\"选择部署环境\",\"type\":\"single_choice\",\"required\":true,"
                        + "\"options\":[{\"id\":\"test\",\"label\":\"测试\"},{\"id\":\"prod\",\"label\":\"生产\"}]},"
                        + "{\"id\":\"note\",\"title\":\"请输入发布说明\",\"type\":\"text\",\"required\":true}"
                        + "]}";
        ScriptedStreamingChatModel streamingModel =
                new ScriptedStreamingChatModel()
                        .enqueueToolCall("call_1", "ask_user", arguments)
                        .enqueueText("流式 AskUser 恢复完成");
        ChatMemoryProvider memoryProvider = new MessageWindowChatMemoryProvider(100);
        PermissionInterruptModule module =
                PermissionInterruptModule.safeDefaults().enableAskUser().build();
        ReActAgent agent = agent(null, streamingModel, toolService(), memoryProvider, module);
        final AtomicReference<AgentSuspendInfo> suspendInfo =
                new AtomicReference<AgentSuspendInfo>();
        final AtomicReference<ChatResponse> complete = new AtomicReference<ChatResponse>();

        agent.runStream(request("需要用户选择发布环境"))
                .onSuspended(consumeSuspend(suspendInfo))
                .onCompleteResponse(consumeResponse(complete))
                .start();

        assertNotNull(suspendInfo.get());
        assertNull(complete.get());
        assertEquals(1, streamingModel.callCount());
        InterruptRequest interrupt = suspendInfo.get().getInterrupts().get(0);
        assertEquals(InterruptType.ASK_USER, interrupt.getType());
        assertEquals(2, interrupt.getAskUserRequest().getQuestions().size());

        InterruptBatchResumeCommand command =
                new InterruptBatchResumeCommand(
                        suspendInfo.get().getBatchId(),
                        Collections.singletonList(
                                InterruptResponse.askUser(
                                        interrupt.getInterruptId(),
                                        AskUserResponse.of(
                                                AskUserAnswer.single("env", "prod"),
                                                AskUserAnswer.text("note", "先灰度发布")))));

        agent.resumeInterruptStream(command).onCompleteResponse(consumeResponse(complete)).start();

        assertNotNull(complete.get());
        assertEquals("流式 AskUser 恢复完成", complete.get().aiMessage().text());
        assertEquals(2, streamingModel.callCount());

        List<ChatMessage> messages = memoryProvider.get(MEMORY_ID).messages();
        ChatMessage toolResult = messages.get(messages.size() - 2);
        assertEquals(ChatMessageType.TOOL_EXECUTION_RESULT, toolResult.type());
        assertTrue(toolResult.text().contains("\"questionId\":\"env\""));
        assertTrue(toolResult.text().contains("\"selectedOptionIds\":[\"prod\"]"));
        assertTrue(toolResult.text().contains("\"questionId\":\"note\""));
        assertTrue(toolResult.text().contains("\"customValue\":\"先灰度发布\""));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectUnknownChoiceOptionBeforeClaimingResume() {
        String arguments =
                "{\"questions\":[{\"id\":\"env\",\"title\":\"选择环境\",\"type\":\"single_choice\","
                        + "\"options\":[{\"id\":\"test\",\"label\":\"测试\"},{\"id\":\"prod\",\"label\":\"生产\"}]}]}";
        ScriptedChatModel chatModel =
                new ScriptedChatModel()
                        .enqueueToolCall("call_1", "ask_user", arguments)
                        .enqueueText("不应执行到这里");
        PermissionInterruptModule module =
                PermissionInterruptModule.safeDefaults().enableAskUser().build();
        ReActAgent agent =
                agent(
                        chatModel,
                        null,
                        toolService(),
                        new MessageWindowChatMemoryProvider(100),
                        module);

        ChatResult suspended = agent.run(request("需要确认"));
        InterruptRequest interrupt = suspended.getSuspendInfo().getInterrupts().get(0);
        agent.resumeInterrupt(
                new InterruptBatchResumeCommand(
                        suspended.getSuspendInfo().getBatchId(),
                        Collections.singletonList(
                                InterruptResponse.askUser(
                                        interrupt.getInterruptId(),
                                        AskUserResponse.single("env", "unknown")))));
    }

    @Test
    public void shouldSuspendAndResumeInterruptByStreaming() {
        ScriptedStreamingChatModel streamingModel =
                new ScriptedStreamingChatModel()
                        .enqueueToolCall("call_1", "getWeather", "{\"city\":\"Hangzhou\"}")
                        .enqueueText("流式恢复后继续完成");
        ChatMemoryProvider memoryProvider = new MessageWindowChatMemoryProvider(100);
        PermissionInterruptModule module =
                PermissionInterruptModule.safeDefaults().ask("getWeather", ".*").build();
        ReActAgent agent = agent(null, streamingModel, toolService(), memoryProvider, module);

        final AtomicReference<AgentSuspendInfo> suspendInfo =
                new AtomicReference<AgentSuspendInfo>();
        final AtomicReference<ChatResponse> complete = new AtomicReference<ChatResponse>();

        agent.runStream(request("杭州天气怎么样？"))
                .onSuspended(consumeSuspend(suspendInfo))
                .onCompleteResponse(consumeResponse(complete))
                .start();

        assertNotNull(suspendInfo.get());
        assertNull(complete.get());
        assertEquals(1, streamingModel.callCount());

        InterruptRequest interrupt = suspendInfo.get().getInterrupts().get(0);
        IInterruptibleAgent resumable = agent;
        resumable
                .resumeInterruptStream(approve(suspendInfo.get(), interrupt))
                .onCompleteResponse(consumeResponse(complete))
                .start();

        assertNotNull(complete.get());
        assertEquals("流式恢复后继续完成", complete.get().aiMessage().text());
        assertEquals(2, streamingModel.callCount());

        List<ChatMessage> messages = memoryProvider.get(MEMORY_ID).messages();
        ChatMessage toolResult = messages.get(messages.size() - 2);
        assertEquals(ChatMessageType.TOOL_EXECUTION_RESULT, toolResult.type());
        assertTrue(toolResult.text().contains("22 degrees Celsius"));
    }

    private static InterruptBatchResumeCommand approve(
            AgentSuspendInfo suspendInfo, InterruptRequest interrupt) {
        return new InterruptBatchResumeCommand(
                suspendInfo.getBatchId(),
                Collections.singletonList(
                        new InterruptResponse(
                                interrupt.getInterruptId(), ApprovalAction.APPROVE_ONCE, null)));
    }

    private static Consumer<AgentSuspendInfo> consumeSuspend(
            final AtomicReference<AgentSuspendInfo> target) {
        return new Consumer<AgentSuspendInfo>() {
            @Override
            public void accept(AgentSuspendInfo info) {
                target.set(info);
            }
        };
    }

    private static Consumer<ChatResponse> consumeResponse(
            final AtomicReference<ChatResponse> target) {
        return new Consumer<ChatResponse>() {
            @Override
            public void accept(ChatResponse response) {
                target.set(response);
            }
        };
    }

    private static ToolService toolService() {
        ToolService toolService = new ToolService();
        toolService.tools(new WeatherTools());
        return toolService;
    }

    private static ReActAgent agent(
            ScriptedChatModel chatModel,
            ScriptedStreamingChatModel streamingModel,
            ToolService toolService,
            ChatMemoryProvider memoryProvider,
            PermissionInterruptModule module) {
        return ReActAgent.builder()
                .agentName("interrupt-agent")
                .description("interrupt resume demo")
                .systemPrompt("You are a weather assistant.")
                .chatModel(chatModel)
                .streamingChatModel(streamingModel)
                .chatMemoryProvider(memoryProvider)
                .toolService(toolService)
                .agentSettings(AgentSettings.builder().aiCallRetry(0).aiCallRetryDelay(0).build())
                .permissionInterruptModule(module)
                .build();
    }

    private static AgentRequest request(String question) {
        return AgentRequest.builder()
                .memoryId(MEMORY_ID)
                .executionId("test-execution")
                .question(question)
                .build();
    }
}
