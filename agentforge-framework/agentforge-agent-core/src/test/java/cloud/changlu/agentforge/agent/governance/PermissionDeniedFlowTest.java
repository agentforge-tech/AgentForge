package cloud.changlu.agentforge.agent.governance;

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
import cloud.changlu.agentforge.agent.memory.ChatMemoryProvider;
import cloud.changlu.agentforge.agent.step.ChatResult;
import cloud.changlu.agentforge.agent.step.ChatResultState;
import cloud.changlu.agentforge.agent.support.ScriptedChatModel;
import cloud.changlu.agentforge.agent.support.ScriptedStreamingChatModel;
import cloud.changlu.agentforge.agent.support.WeatherTools;
import cloud.changlu.agentforge.model.chat.message.ChatMessage;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionResultMessage;
import cloud.changlu.agentforge.model.chat.response.ChatResponse;
import cloud.changlu.agentforge.model.tool.execution.ToolExecution;
import cloud.changlu.agentforge.model.tool.execution.ToolService;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 验证权限引擎直接 DENY 时写拒绝 Tool Result 并继续 ReAct，流式与非流式一致。 */
public class PermissionDeniedFlowTest {

    private static final Object MEMORY_ID = "permission-denied-session";

    @Test
    public void shouldWriteDeniedToolResultAndContinueByNonStreaming() {
        ScriptedChatModel chatModel =
                new ScriptedChatModel()
                        .enqueueToolCall("call_1", "shell", "{}")
                        .enqueueText("已根据拒绝结果继续");
        ChatMemoryProvider memoryProvider = new MessageWindowChatMemoryProvider(100);
        ReActAgent agent =
                agent(
                        chatModel,
                        null,
                        memoryProvider,
                        PermissionInterruptModule.safeDefaults().deny("shell", ".*").build());

        ChatResult result = agent.run(request("执行命令"));

        assertEquals(ChatResultState.FINISHED, result.getChatResultState());
        assertEquals(AgentRunState.SUCCESS, result.getRunState());
        assertEquals("已根据拒绝结果继续", result.getRes());
        assertEquals(2, chatModel.callCount());

        ToolExecutionResultMessage denied = toolResult(memoryProvider, "call_1");
        assertTrue(Boolean.TRUE.equals(denied.isError()));
        assertTrue(denied.text().contains("denied"));
    }

    @Test
    public void shouldResumeMixedDeniedAndApprovedBatchByNonStreaming() {
        ScriptedChatModel chatModel =
                new ScriptedChatModel()
                        .enqueueToolCalls(
                                Arrays.asList(
                                        ToolExecutionRequest.from("call_1", "shell", "{}"),
                                        ToolExecutionRequest.from(
                                                "call_2", "getWeather", "{\"city\":\"Hangzhou\"}")))
                        .enqueueText("混合批次恢复完成");
        ChatMemoryProvider memoryProvider = new MessageWindowChatMemoryProvider(100);
        PermissionInterruptModule module =
                PermissionInterruptModule.safeDefaults()
                        .deny("shell", ".*")
                        .ask("getWeather", ".*")
                        .build();
        ReActAgent agent = agent(chatModel, null, memoryProvider, module);

        ChatResult suspended = agent.run(request("执行混合工具"));
        assertEquals(ChatResultState.SUSPENDED, suspended.getChatResultState());
        assertEquals(
                1,
                module.getInterruptStore()
                        .get(suspended.getSuspendInfo().getBatchId())
                        .getDenials()
                        .size());
        InterruptRequest interrupt = suspended.getSuspendInfo().getInterrupts().get(0);

        ChatResult resumed =
                agent.resumeInterrupt(
                        new InterruptBatchResumeCommand(
                                suspended.getSuspendInfo().getBatchId(),
                                Collections.singletonList(
                                        new InterruptResponse(
                                                interrupt.getInterruptId(),
                                                ApprovalAction.APPROVE_ONCE,
                                                null))));

        assertEquals(AgentRunState.SUCCESS, resumed.getRunState());
        assertEquals("混合批次恢复完成", resumed.getRes());

        List<ToolExecutionResultMessage> results = toolResults(memoryProvider);
        assertEquals(2, results.size());
        assertEquals("call_1", results.get(0).id());
        assertTrue(Boolean.TRUE.equals(results.get(0).isError()));
        assertEquals("call_2", results.get(1).id());
        assertTrue(results.get(1).text().contains("22 degrees Celsius"));
    }

    @Test
    public void shouldWriteDeniedToolResultAndContinueByStreaming() {
        ScriptedStreamingChatModel streamingModel =
                new ScriptedStreamingChatModel()
                        .enqueueToolCall("call_1", "shell", "{}")
                        .enqueueText("流式拒绝后继续");
        ChatMemoryProvider memoryProvider = new MessageWindowChatMemoryProvider(100);
        ReActAgent agent =
                agent(
                        null,
                        streamingModel,
                        memoryProvider,
                        PermissionInterruptModule.safeDefaults().deny("shell", ".*").build());

        final AtomicReference<ChatResponse> complete = new AtomicReference<ChatResponse>();
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        final List<ToolExecution> executions = new ArrayList<ToolExecution>();

        agent.runStream(request("执行命令"))
                .onToolExecuted(consumeTool(executions))
                .onCompleteResponse(consumeResponse(complete))
                .onError(consumeError(error))
                .start();

        assertNull(error.get());
        assertNotNull(complete.get());
        assertEquals("流式拒绝后继续", complete.get().aiMessage().text());
        assertEquals(2, streamingModel.callCount());
        assertEquals(1, executions.size());
        assertTrue(executions.get(0).hasFailed());

        ToolExecutionResultMessage denied = toolResult(memoryProvider, "call_1");
        assertTrue(Boolean.TRUE.equals(denied.isError()));
        assertTrue(denied.text().contains("denied"));
    }

    @Test
    public void shouldSuspendAndResumeMixedBatchByStreaming() {
        ScriptedStreamingChatModel streamingModel =
                new ScriptedStreamingChatModel()
                        .enqueueToolCalls(
                                Arrays.asList(
                                        ToolExecutionRequest.from("call_1", "shell", "{}"),
                                        ToolExecutionRequest.from(
                                                "call_2", "getWeather", "{\"city\":\"Hangzhou\"}")))
                        .enqueueText("流式混合批次恢复完成");
        ChatMemoryProvider memoryProvider = new MessageWindowChatMemoryProvider(100);
        PermissionInterruptModule module =
                PermissionInterruptModule.safeDefaults()
                        .deny("shell", ".*")
                        .ask("getWeather", ".*")
                        .build();
        ReActAgent agent = agent(null, streamingModel, memoryProvider, module);

        final AtomicReference<AgentSuspendInfo> suspendInfo =
                new AtomicReference<AgentSuspendInfo>();
        final AtomicReference<ChatResponse> complete = new AtomicReference<ChatResponse>();
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();

        agent.runStream(request("执行混合工具"))
                .onSuspended(consumeSuspend(suspendInfo))
                .onCompleteResponse(consumeResponse(complete))
                .onError(consumeError(error))
                .start();

        assertNull(error.get());
        assertNotNull(suspendInfo.get());
        assertNull(complete.get());
        assertEquals(1, streamingModel.callCount());

        InterruptRequest interrupt = suspendInfo.get().getInterrupts().get(0);
        agent.resumeInterruptStream(
                        new InterruptBatchResumeCommand(
                                suspendInfo.get().getBatchId(),
                                Collections.singletonList(
                                        new InterruptResponse(
                                                interrupt.getInterruptId(),
                                                ApprovalAction.APPROVE_ONCE,
                                                null))))
                .onCompleteResponse(consumeResponse(complete))
                .onError(consumeError(error))
                .start();

        assertNull(error.get());
        assertNotNull(complete.get());
        assertEquals("流式混合批次恢复完成", complete.get().aiMessage().text());
        assertEquals(2, streamingModel.callCount());

        List<ToolExecutionResultMessage> results = toolResults(memoryProvider);
        assertEquals(2, results.size());
        assertEquals("call_1", results.get(0).id());
        assertTrue(Boolean.TRUE.equals(results.get(0).isError()));
        assertEquals("call_2", results.get(1).id());
        assertTrue(results.get(1).text().contains("22 degrees Celsius"));
    }

    private static ToolExecutionResultMessage toolResult(
            ChatMemoryProvider memoryProvider, String toolCallId) {
        for (ChatMessage message : memoryProvider.get(MEMORY_ID).messages()) {
            if (message instanceof ToolExecutionResultMessage
                    && toolCallId.equals(((ToolExecutionResultMessage) message).id())) {
                return (ToolExecutionResultMessage) message;
            }
        }
        throw new AssertionError("tool result not found: " + toolCallId);
    }

    private static List<ToolExecutionResultMessage> toolResults(ChatMemoryProvider memoryProvider) {
        List<ToolExecutionResultMessage> results = new ArrayList<ToolExecutionResultMessage>();
        for (ChatMessage message : memoryProvider.get(MEMORY_ID).messages()) {
            if (message instanceof ToolExecutionResultMessage) {
                results.add((ToolExecutionResultMessage) message);
            }
        }
        return results;
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

    private static Consumer<Throwable> consumeError(final AtomicReference<Throwable> target) {
        return new Consumer<Throwable>() {
            @Override
            public void accept(Throwable throwable) {
                target.set(throwable);
            }
        };
    }

    private static Consumer<ToolExecution> consumeTool(final List<ToolExecution> target) {
        return new Consumer<ToolExecution>() {
            @Override
            public void accept(ToolExecution execution) {
                target.add(execution);
            }
        };
    }

    private static ReActAgent agent(
            ScriptedChatModel chatModel,
            ScriptedStreamingChatModel streamingModel,
            ChatMemoryProvider memoryProvider,
            PermissionInterruptModule module) {
        ToolService toolService = new ToolService();
        toolService.tools(new WeatherTools());
        return ReActAgent.builder()
                .agentName("permission-denied-agent")
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
