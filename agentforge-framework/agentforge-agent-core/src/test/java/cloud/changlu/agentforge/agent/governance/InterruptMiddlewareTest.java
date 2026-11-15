package cloud.changlu.agentforge.agent.governance;

import cloud.changlu.agentforge.agent.IInterruptibleAgent;
import cloud.changlu.agentforge.agent.ReActAgent;
import cloud.changlu.agentforge.agent.component.middleware.IAgentMiddleware;
import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.domain.AgentRequest;
import cloud.changlu.agentforge.agent.domain.AgentRunState;
import cloud.changlu.agentforge.agent.domain.AgentSettings;
import cloud.changlu.agentforge.agent.extend.memory.window.MessageWindowChatMemoryProvider;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.AgentSuspendInfo;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.ApprovalAction;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptBatchResumeCommand;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptRequest;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptResponse;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptResumeContext;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserAnswer;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserResponse;
import cloud.changlu.agentforge.agent.memory.ChatMemoryProvider;
import cloud.changlu.agentforge.agent.step.ChatResult;
import cloud.changlu.agentforge.agent.support.ScriptedChatModel;
import cloud.changlu.agentforge.agent.support.ScriptedStreamingChatModel;
import cloud.changlu.agentforge.agent.support.WeatherTools;
import cloud.changlu.agentforge.model.chat.response.ChatResponse;
import cloud.changlu.agentforge.model.tool.execution.ToolService;

import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 验证中断挂起、恢复前/后以及决策感知的中间件回调。 */
public class InterruptMiddlewareTest {

    private static final Object MEMORY_ID = "interrupt-middleware-session";

    @Test
    public void shouldNotifyInterruptAndResumeWithApprovalDecision() {
        ScriptedChatModel chatModel =
                new ScriptedChatModel()
                        .enqueueToolCall("call_1", "getWeather", "{\"city\":\"Hangzhou\"}")
                        .enqueueText("恢复后继续完成");
        RecordingMiddleware middleware = new RecordingMiddleware();
        PermissionInterruptModule module =
                PermissionInterruptModule.safeDefaults().ask("getWeather", ".*").build();
        ReActAgent agent =
                agent(
                        chatModel,
                        null,
                        new MessageWindowChatMemoryProvider(100),
                        module,
                        middleware);

        ChatResult suspended = agent.run(request("杭州天气怎么样？"));
        InterruptRequest interrupt = suspended.getSuspendInfo().getInterrupts().get(0);

        assertNotNull(middleware.requested.get());
        assertEquals(
                suspended.getSuspendInfo().getBatchId(), middleware.requested.get().getBatchId());
        assertNull(middleware.beforeResume.get());
        assertNull(middleware.afterResume.get());

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
        assertNotNull(middleware.beforeResume.get());
        assertNotNull(middleware.afterResume.get());
        assertEquals(
                ApprovalAction.APPROVE_ONCE,
                middleware.beforeResume.get().approvalActionOf(interrupt.getInterruptId()));
        assertTrue(middleware.beforeResume.get().getAskUserAnswers().isEmpty());
        assertNull(middleware.resumeError.get());
    }

    @Test
    public void shouldExposeStructuredAskUserAnswerOnResume() {
        String arguments =
                "{\"questions\":["
                        + "{\"id\":\"env\",\"title\":\"选择环境\",\"type\":\"single_choice\","
                        + "\"options\":[{\"id\":\"test\",\"label\":\"测试\"},{\"id\":\"prod\",\"label\":\"生产\"}]},"
                        + "{\"id\":\"note\",\"title\":\"补充说明\",\"type\":\"text\"}"
                        + "]}";
        ScriptedChatModel chatModel =
                new ScriptedChatModel()
                        .enqueueToolCall("call_1", "ask_user", arguments)
                        .enqueueText("已按回答继续");
        RecordingMiddleware middleware = new RecordingMiddleware();
        PermissionInterruptModule module =
                PermissionInterruptModule.safeDefaults().enableAskUser().build();
        ReActAgent agent =
                agent(
                        chatModel,
                        null,
                        new MessageWindowChatMemoryProvider(100),
                        module,
                        middleware);

        ChatResult suspended = agent.run(request("需要用户确认"));
        InterruptRequest interrupt = suspended.getSuspendInfo().getInterrupts().get(0);

        agent.resumeInterrupt(
                new InterruptBatchResumeCommand(
                        suspended.getSuspendInfo().getBatchId(),
                        Collections.singletonList(
                                InterruptResponse.askUser(
                                        interrupt.getInterruptId(),
                                        AskUserResponse.of(
                                                AskUserAnswer.single("env", "prod"),
                                                AskUserAnswer.text("note", "灰度发布"))))));

        AskUserResponse answer =
                middleware.afterResume.get().askUserAnswerOf(interrupt.getInterruptId());
        assertNotNull(answer);
        assertEquals(2, answer.getAnswers().size());
        assertEquals("prod", answer.getAnswers().get(0).getSelectedOptionIds().get(0));
        assertEquals("灰度发布", answer.getAnswers().get(1).getCustomValue());
        assertEquals(
                2,
                middleware
                        .afterResume
                        .get()
                        .getAskUserAnswers()
                        .get(interrupt.getInterruptId())
                        .getAnswers()
                        .size());
    }

    @Test
    public void shouldNotifyStreamingInterruptAndResume() {
        ScriptedStreamingChatModel streamingModel =
                new ScriptedStreamingChatModel()
                        .enqueueToolCall("call_1", "getWeather", "{\"city\":\"Hangzhou\"}")
                        .enqueueText("流式恢复后继续完成");
        RecordingMiddleware middleware = new RecordingMiddleware();
        PermissionInterruptModule module =
                PermissionInterruptModule.safeDefaults().ask("getWeather", ".*").build();
        ReActAgent agent =
                agent(
                        null,
                        streamingModel,
                        new MessageWindowChatMemoryProvider(100),
                        module,
                        middleware);

        final AtomicReference<AgentSuspendInfo> suspendInfo =
                new AtomicReference<AgentSuspendInfo>();
        final AtomicReference<ChatResponse> complete = new AtomicReference<ChatResponse>();

        agent.runStream(request("杭州天气怎么样？"))
                .onSuspended(consumeSuspend(suspendInfo))
                .onCompleteResponse(consumeResponse(complete))
                .start();

        assertNotNull(middleware.requested.get());
        assertNotNull(suspendInfo.get());
        assertNull(complete.get());

        InterruptRequest interrupt = suspendInfo.get().getInterrupts().get(0);
        IInterruptibleAgent resumable = agent;
        resumable
                .resumeInterruptStream(
                        new InterruptBatchResumeCommand(
                                suspendInfo.get().getBatchId(),
                                Collections.singletonList(
                                        new InterruptResponse(
                                                interrupt.getInterruptId(),
                                                ApprovalAction.APPROVE_ONCE,
                                                null))))
                .onCompleteResponse(consumeResponse(complete))
                .start();

        assertNotNull(complete.get());
        assertEquals("流式恢复后继续完成", complete.get().aiMessage().text());
        assertNotNull(middleware.beforeResume.get());
        assertNotNull(middleware.afterResume.get());
        assertEquals(
                ApprovalAction.APPROVE_ONCE,
                middleware.afterResume.get().approvalActionOf(interrupt.getInterruptId()));
    }

    @Test
    public void shouldKeepRunningWhenInterruptMiddlewareThrows() {
        ScriptedChatModel chatModel =
                new ScriptedChatModel()
                        .enqueueToolCall("call_1", "getWeather", "{\"city\":\"Hangzhou\"}")
                        .enqueueText("中间件异常不影响恢复");
        IAgentMiddleware throwing =
                new IAgentMiddleware() {
                    @Override
                    public void onInterruptRequested(
                            int currentStep,
                            AgentSuspendInfo suspendInfo,
                            AgentChatContext chatContext) {
                        throw new IllegalStateException("boom-request");
                    }

                    @Override
                    public void onBeforeInterruptResume(
                            InterruptResumeContext resumeContext, AgentChatContext chatContext) {
                        throw new IllegalStateException("boom-before");
                    }

                    @Override
                    public void onAfterInterruptResume(
                            InterruptResumeContext resumeContext, AgentChatContext chatContext) {
                        throw new IllegalStateException("boom-after");
                    }
                };
        PermissionInterruptModule module =
                PermissionInterruptModule.safeDefaults().ask("getWeather", ".*").build();
        ReActAgent agent =
                agent(chatModel, null, new MessageWindowChatMemoryProvider(100), module, throwing);

        ChatResult suspended = agent.run(request("杭州天气怎么样？"));
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
        assertEquals("中间件异常不影响恢复", resumed.getRes());
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

    private static ReActAgent agent(
            ScriptedChatModel chatModel,
            ScriptedStreamingChatModel streamingModel,
            ChatMemoryProvider memoryProvider,
            PermissionInterruptModule module,
            IAgentMiddleware middleware) {
        ToolService toolService = new ToolService();
        toolService.tools(new WeatherTools());
        return ReActAgent.builder()
                .agentName("interrupt-middleware-agent")
                .systemPrompt("You are a weather assistant.")
                .chatModel(chatModel)
                .streamingChatModel(streamingModel)
                .chatMemoryProvider(memoryProvider)
                .toolService(toolService)
                .agentSettings(AgentSettings.builder().aiCallRetry(0).aiCallRetryDelay(0).build())
                .permissionInterruptModule(module)
                .middleware(middleware)
                .build();
    }

    private static AgentRequest request(String question) {
        return AgentRequest.builder()
                .memoryId(MEMORY_ID)
                .executionId("test-execution")
                .question(question)
                .build();
    }

    private static final class RecordingMiddleware implements IAgentMiddleware {
        private final AtomicReference<AgentSuspendInfo> requested =
                new AtomicReference<AgentSuspendInfo>();
        private final AtomicReference<InterruptResumeContext> beforeResume =
                new AtomicReference<InterruptResumeContext>();
        private final AtomicReference<InterruptResumeContext> afterResume =
                new AtomicReference<InterruptResumeContext>();
        private final AtomicReference<Throwable> resumeError = new AtomicReference<Throwable>();

        @Override
        public void onInterruptRequested(
                int currentStep, AgentSuspendInfo suspendInfo, AgentChatContext chatContext) {
            requested.set(suspendInfo);
        }

        @Override
        public void onBeforeInterruptResume(
                InterruptResumeContext resumeContext, AgentChatContext chatContext) {
            beforeResume.set(resumeContext);
        }

        @Override
        public void onAfterInterruptResume(
                InterruptResumeContext resumeContext, AgentChatContext chatContext) {
            afterResume.set(resumeContext);
        }

        @Override
        public void onInterruptResumeError(
                InterruptResumeContext resumeContext,
                Throwable error,
                AgentChatContext chatContext) {
            resumeError.set(error);
        }
    }
}
