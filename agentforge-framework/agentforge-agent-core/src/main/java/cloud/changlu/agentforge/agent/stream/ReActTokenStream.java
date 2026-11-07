package cloud.changlu.agentforge.agent.stream;

import cloud.changlu.agentforge.agent.component.middleware.AgentMiddlewareManager;
import cloud.changlu.agentforge.agent.component.state.compensation.CancellationMessageCompensator;
import cloud.changlu.agentforge.agent.component.state.control.AgentRunControl;
import cloud.changlu.agentforge.agent.component.state.control.AgentRunRegistry;
import cloud.changlu.agentforge.agent.component.state.domain.AgentExecutionPhase;
import cloud.changlu.agentforge.agent.component.state.domain.AgentExecutionStatus;
import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.domain.AgentRunState;
import cloud.changlu.agentforge.agent.domain.AgentSettings;
import cloud.changlu.agentforge.agent.domain.StopResult;
import cloud.changlu.agentforge.agent.exception.AgentException;
import cloud.changlu.agentforge.agent.exception.CancelException;
import cloud.changlu.agentforge.agent.step.StepResult;
import cloud.changlu.agentforge.agent.step.StopResultState;
import cloud.changlu.agentforge.agent.tool.AgentToolExecutor;
import cloud.changlu.agentforge.model.chat.StreamingChatModel;
import cloud.changlu.agentforge.model.chat.message.AiMessage;
import cloud.changlu.agentforge.model.chat.request.ChatRequest;
import cloud.changlu.agentforge.model.chat.request.DefaultChatRequestParameters;
import cloud.changlu.agentforge.model.chat.response.ChatResponse;
import cloud.changlu.agentforge.model.chat.response.StreamingChatResponseHandler;
import cloud.changlu.agentforge.model.tool.execution.ToolExecution;
import cloud.changlu.agentforge.model.tool.spec.ToolSpecification;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * @description ReAct流式执行：每一轮用StreamingChatModel发起请求，遇到工具调用则执行act并自动进入下一轮，
 *     直到模型给出最终回答或达到步长上限；执行过程同步触发中间件回调
 * @author changlu
 * @date 2026/9/16
 */
public class ReActTokenStream implements TokenStream {

    private static final Logger log = Logger.getLogger(ReActTokenStream.class.getName());

    private static final String MODEL_CALL_INTERRUPTED = "模型调用被中间件中断";

    private static final String MAX_STEPS_PREFIX = "执行步长达到限制";

    /** 未显式配置 AgentSettings 时的默认重试次数，与 AgentSettings 默认值保持一致。 */
    private static final int DEFAULT_AI_CALL_RETRY = 3;

    /** 未显式配置 AgentSettings 时的默认首次重试等待时间。 */
    private static final long DEFAULT_AI_CALL_RETRY_DELAY_MS = 2000L;

    /** 流式重试的延迟调度器：不在模型回调线程内 sleep，避免阻塞底层 HTTP 客户端线程。 */
    private static final ScheduledExecutorService RETRY_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(
                    new ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable runnable) {
                            Thread thread = new Thread(runnable, "react-stream-retry");
                            thread.setDaemon(true);
                            return thread;
                        }
                    });

    private final AgentChatContext chatContext;
    private final StreamingChatModel streamingChatModel;
    private final AgentToolExecutor toolExecutor;
    private final List<ToolSpecification> toolSpecifications;
    private final AgentSettings agentSettings;
    private final AgentMiddlewareManager middlewareManager;
    private final AgentRunControl runControl;
    private final AgentRunRegistry runRegistry;

    private Consumer<String> partialResponseHandler;
    private Consumer<PartialThinking> partialThinkingHandler;
    private Consumer<ChatResponse> intermediateResponseHandler;
    private Consumer<ToolExecution> toolExecutedHandler;
    private Consumer<ChatResponse> completeResponseHandler;
    private Consumer<Throwable> errorHandler;
    private boolean ignoreErrors;

    // 本轮实际发起模型调用所用的请求，供afterModelCall等中间件回调读取
    private ChatRequest currentChatRequest;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean finalized = new AtomicBoolean(false);
    private final AtomicBoolean cancelHandled = new AtomicBoolean(false);
    private volatile int activeStep = 1;

    public ReActTokenStream(
            AgentChatContext chatContext,
            StreamingChatModel streamingChatModel,
            AgentToolExecutor toolExecutor,
            List<ToolSpecification> toolSpecifications,
            AgentSettings agentSettings) {
        this(
                chatContext,
                streamingChatModel,
                toolExecutor,
                toolSpecifications,
                agentSettings,
                null,
                null,
                null);
    }

    public ReActTokenStream(
            AgentChatContext chatContext,
            StreamingChatModel streamingChatModel,
            AgentToolExecutor toolExecutor,
            List<ToolSpecification> toolSpecifications,
            AgentSettings agentSettings,
            AgentMiddlewareManager middlewareManager,
            AgentRunControl runControl,
            AgentRunRegistry runRegistry) {
        if (streamingChatModel == null) {
            throw new AgentException("streamingChatModel is null, need set");
        }
        this.chatContext = chatContext;
        this.streamingChatModel = streamingChatModel;
        this.toolExecutor = toolExecutor;
        this.toolSpecifications =
                toolSpecifications == null
                        ? new ArrayList<ToolSpecification>()
                        : toolSpecifications;
        this.agentSettings = agentSettings;
        this.middlewareManager = middlewareManager;
        this.runControl = runControl;
        this.runRegistry = runRegistry;
        if (this.runControl != null) {
            this.runControl.onCancel(
                    new Runnable() {
                        @Override
                        public void run() {
                            handleCancel(
                                    activeStep,
                                    String.format(
                                            "会话：%s 流式执行被取消",
                                            ReActTokenStream.this.chatContext.getMemoryId()));
                        }
                    });
        }
    }

    @Override
    public String executionId() {
        return chatContext.getExecutionId();
    }

    @Override
    public TokenStream onPartialResponse(Consumer<String> partialResponseHandler) {
        this.partialResponseHandler = partialResponseHandler;
        return this;
    }

    @Override
    public TokenStream onPartialThinking(Consumer<PartialThinking> partialThinkingHandler) {
        this.partialThinkingHandler = partialThinkingHandler;
        return this;
    }

    @Override
    public TokenStream onIntermediateResponse(Consumer<ChatResponse> intermediateResponseHandler) {
        this.intermediateResponseHandler = intermediateResponseHandler;
        return this;
    }

    @Override
    public TokenStream onToolExecuted(Consumer<ToolExecution> toolExecuteHandler) {
        this.toolExecutedHandler = toolExecuteHandler;
        return this;
    }

    @Override
    public TokenStream onCompleteResponse(Consumer<ChatResponse> completeResponseHandler) {
        this.completeResponseHandler = completeResponseHandler;
        return this;
    }

    @Override
    public TokenStream onError(Consumer<Throwable> errorHandler) {
        this.errorHandler = errorHandler;
        return this;
    }

    @Override
    public TokenStream ignoreErrors() {
        this.ignoreErrors = true;
        return this;
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new AgentException("TokenStream已经启动，不能重复start");
        }
        if (middlewareManager != null) {
            // 触发中间件初始化完成回调（只执行一次）
            middlewareManager.triggerOnInitComplete(chatContext);
        }
        if (runControl != null) {
            runControl.registerExecutionThread(Thread.currentThread());
            runControl.running(AgentExecutionPhase.BEFORE_LOOP, 1);
        }
        log.fine(
                "ReActTokenStream start executionId="
                        + chatContext.getExecutionId()
                        + ", memoryId="
                        + chatContext.getMemoryId());
        try {
            chat(1);
        } catch (RuntimeException e) {
            // 模型调用是异步的，这里的同步异常统一走错误回调，避免直接抛到调用线程
            handleError(1, e);
        }
    }

    private void chat(final int currentStep) {
        activeStep = currentStep;
        log.fine(
                "ReActTokenStream chat executionId="
                        + chatContext.getExecutionId()
                        + ", step="
                        + currentStep);
        if (isCancelled()) {
            handleCancel(
                    currentStep,
                    String.format("会话：%s 在第%d步被取消", chatContext.getMemoryId(), currentStep));
            return;
        }
        int maxSteps = agentSettings.getMaxSteps();
        if (currentStep > maxSteps) {
            handleError(currentStep, new AgentException(MAX_STEPS_PREFIX + "，请重新询问你的问题！"));
            return;
        }
        if (runControl != null) runControl.running(AgentExecutionPhase.BEFORE_LOOP, currentStep);

        DefaultChatRequestParameters.Builder parameters = DefaultChatRequestParameters.builder();
        if (!toolSpecifications.isEmpty()) {
            parameters.tools(new ArrayList<ToolSpecification>(toolSpecifications));
        }
        ChatRequest chatRequest =
                ChatRequest.builder()
                        .messages(chatContext.getChatMemory().messages())
                        .parameters(parameters.build())
                        .build();

        ChatRequest processedRequest = chatRequest;
        if (middlewareManager != null) {
            middlewareManager.triggerBeforeLoop(currentStep, chatContext);
            processedRequest =
                    middlewareManager.triggerBeforeModelCall(currentStep, chatRequest, chatContext);
            if (processedRequest == null) {
                // 中间件中断本轮模型调用，按取消态收敛后结束；必须先收口，避免 registry 残留活跃执行
                finishExecution(
                        AgentExecutionStatus.CANCELLED,
                        AgentRunState.CANCEL,
                        MODEL_CALL_INTERRUPTED);
                notifyStop(
                        currentStep,
                        StopResultState.CANCEL,
                        MODEL_CALL_INTERRUPTED,
                        new AgentException(MODEL_CALL_INTERRUPTED));
                notifyError(new AgentException(MODEL_CALL_INTERRUPTED));
                return;
            }
        }
        currentChatRequest = processedRequest;

        attemptChat(currentStep, processedRequest, 0);
    }

    /**
     * 发起一次流式模型调用。
     *
     * <p>流式调用是异步的，失败可能表现为同步异常，也可能通过 {@code onError} 回调返回。两种入口统一收敛到 {@link #handleAttemptError}，再按
     * {@link AgentSettings} 的重试配置决定重试或失败。
     *
     * <p>只有本次尝试尚未输出任何增量内容时才允许重试：已经下发给页面的 think/resp token 无法撤回， 重试会造成内容重复。
     */
    private void attemptChat(
            final int currentStep, final ChatRequest request, final int retriedCount) {
        if (runControl != null) runControl.modelCall(currentStep);
        log.fine(
                "ReActTokenStream 发起模型调用 executionId="
                        + chatContext.getExecutionId()
                        + ", step="
                        + currentStep
                        + ", retry="
                        + retriedCount);
        final AtomicBoolean resolved = new AtomicBoolean(false);
        final AtomicBoolean hadPartial = new AtomicBoolean(false);
        StreamingChatResponseHandler handler =
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partialResponse) {
                        if (resolved.get() || !acceptCallback()) {
                            return;
                        }
                        hadPartial.set(true);
                        if (middlewareManager != null) {
                            middlewareManager.triggerOnPartialResponse(
                                    currentStep, partialResponse, chatContext);
                        }
                        if (partialResponseHandler != null) {
                            partialResponseHandler.accept(partialResponse);
                        }
                    }

                    @Override
                    public void onPartialThinking(String partialThinking) {
                        if (resolved.get() || !acceptCallback()) {
                            return;
                        }
                        hadPartial.set(true);
                        PartialThinking thinking = new PartialThinking(partialThinking);
                        if (middlewareManager != null) {
                            middlewareManager.triggerOnPartialThinking(
                                    currentStep, thinking, chatContext);
                        }
                        if (partialThinkingHandler != null) {
                            partialThinkingHandler.accept(thinking);
                        }
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse completeResponse) {
                        if (!acceptCallback()) return;
                        if (!resolved.compareAndSet(false, true)) {
                            return;
                        }
                        onRoundComplete(currentStep, completeResponse);
                    }

                    @Override
                    public void onError(Throwable error) {
                        if (!acceptCallback()) return;
                        if (!resolved.compareAndSet(false, true)) {
                            return;
                        }
                        handleAttemptError(
                                currentStep, request, retriedCount, hadPartial.get(), error);
                    }
                };
        try {
            if (runControl != null) {
                runControl.registerModelHandle(
                        streamingChatModel.chatCancellable(request, handler));
            } else {
                streamingChatModel.chat(request, handler);
            }
        } catch (RuntimeException e) {
            if (resolved.compareAndSet(false, true)) {
                handleAttemptError(currentStep, request, retriedCount, hadPartial.get(), e);
            }
        }
    }

    private void handleAttemptError(
            int currentStep,
            ChatRequest request,
            int retriedCount,
            boolean hadPartial,
            Throwable error) {
        if (isCancelled()) {
            handleCancel(
                    currentStep,
                    String.format("会话：%s 在第%d步被取消", chatContext.getMemoryId(), currentStep));
            return;
        }
        if (!hadPartial && retriedCount < getMaxAiCallRetries()) {
            scheduleRetry(currentStep, request, retriedCount + 1, error);
            return;
        }
        handleError(currentStep, error);
    }

    private void scheduleRetry(
            final int currentStep,
            final ChatRequest request,
            final int retryCount,
            final Throwable lastError) {
        long delayMs = retryDelay(retryCount);
        if (runControl != null) runControl.retryWait();
        if (middlewareManager != null) {
            middlewareManager.triggerOnAiCallRetry(
                    currentStep,
                    request,
                    chatContext,
                    retryCount,
                    getMaxAiCallRetries(),
                    delayMs,
                    asException(lastError));
        }
        Runnable retryTask =
                new Runnable() {
                    @Override
                    public void run() {
                        if (isCancelled()) {
                            handleCancel(
                                    currentStep,
                                    String.format(
                                            "会话：%s 在第%d步被取消",
                                            chatContext.getMemoryId(), currentStep));
                            return;
                        }
                        attemptChat(currentStep, request, retryCount);
                    }
                };
        if (delayMs <= 0L) {
            retryTask.run();
        } else {
            ScheduledFuture<?> retryFuture =
                    RETRY_SCHEDULER.schedule(retryTask, delayMs, TimeUnit.MILLISECONDS);
            if (runControl != null) runControl.registerFuture(retryFuture);
        }
    }

    private int getMaxAiCallRetries() {
        return agentSettings == null
                ? DEFAULT_AI_CALL_RETRY
                : Math.max(agentSettings.getAiCallRetry(), 0);
    }

    private long retryDelay(int retryCount) {
        long base =
                agentSettings == null
                        ? DEFAULT_AI_CALL_RETRY_DELAY_MS
                        : Math.max(agentSettings.getAiCallRetryDelay(), 0L);
        long delay = base;
        // retryCount 从 1 开始：第 1 次重试等待 base，之后每次翻倍
        for (int i = 1; i < retryCount && delay > 0L; i++) {
            delay = delay > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : delay * 2;
        }
        return delay;
    }

    private static Exception asException(Throwable error) {
        if (error instanceof Exception) {
            return (Exception) error;
        }
        return new AgentException(error.getMessage(), error);
    }

    private void onRoundComplete(int currentStep, ChatResponse response) {
        if (!acceptCallback()) return;
        if (middlewareManager != null) {
            // 触发模型调用后的中间件
            response =
                    middlewareManager.triggerAfterModelCall(
                            currentStep, currentChatRequest, response, chatContext);
        }

        if (isCancelled()) {
            handleCancel(currentStep, String.format("会话：%s 流式执行被取消", chatContext.getMemoryId()));
            return;
        }

        AiMessage aiMessage = response.aiMessage();
        log.fine(
                "ReActTokenStream 收到模型响应 executionId="
                        + chatContext.getExecutionId()
                        + ", step="
                        + currentStep
                        + ", toolCalls="
                        + aiMessage.toolExecutionRequests().size());
        chatContext.getChatMemory().add(aiMessage);

        // check是否还需要tools调用情况
        if (!aiMessage.hasToolExecutionRequests()) {
            if (middlewareManager != null) {
                middlewareManager.triggerAfterLoop(
                        currentStep, StepResult.toStop(aiMessage.text()), chatContext);
                middlewareManager.triggerOnStop(
                        currentStep,
                        stopResult(StopResultState.NORMAL, aiMessage.text()),
                        chatContext);
            }
            finishExecution(AgentExecutionStatus.COMPLETED, AgentRunState.SUCCESS, null);
            handleComplete(response);
            return;
        }

        // act：执行本轮工具并把结果写回记忆，随后自动进入下一轮think
        if (runControl != null) {
            runControl.beginTools(aiMessage.toolExecutionRequests(), currentStep);
        }
        toolExecutor.execute(
                currentStep,
                aiMessage.toolExecutionRequests(),
                chatContext,
                toolExecutedHandler,
                middlewareManager,
                agentSettings != null && agentSettings.isEnableConcurrentToolExecution(),
                runControl);
        if (middlewareManager != null) {
            // 中间响应：本轮的模型响应携带工具调用请求，工具执行完成后在下一轮思考前触发
            middlewareManager.triggerOnIntermediateResponse(currentStep, response, chatContext);
        }
        if (intermediateResponseHandler != null) {
            intermediateResponseHandler.accept(response);
        }
        if (middlewareManager != null) {
            // 与非流式循环保持一致：afterLoop 在本轮工具执行完成后触发，状态为 FINISHED
            middlewareManager.triggerAfterLoop(currentStep, StepResult.toFinished(""), chatContext);
        }
        if (runControl != null) runControl.betweenSteps(currentStep + 1);
        chat(currentStep + 1);
    }

    private void handleComplete(ChatResponse response) {
        if (completeResponseHandler != null) {
            completeResponseHandler.accept(response);
        }
    }

    // 取消场景：停止回调在这里统一触发，避免与handleError重复
    private void handleCancel(int currentStep, String message) {
        if (!cancelHandled.compareAndSet(false, true)) return;
        if (runControl != null && runControl.isTerminal()) return;
        log.fine(
                "ReActTokenStream 取消 executionId="
                        + chatContext.getExecutionId()
                        + ", step="
                        + currentStep
                        + ", message="
                        + message);
        Thread.interrupted();
        if (runControl != null) runControl.compensating();
        CancellationMessageCompensator.compensate(chatContext, message);
        finishExecution(AgentExecutionStatus.CANCELLED, AgentRunState.CANCEL, null);
        notifyStop(currentStep, StopResultState.CANCEL, message, new CancelException(message));
        notifyError(new CancelException(message));
    }

    // 触发中间件的错误/停止回调，再交给上层错误处理器
    private void handleError(int currentStep, Throwable error) {
        if (runControl != null && runControl.isTerminal()) return;

        // 取消异常统一走 handleCancel：消息补偿 + onStop(CANCEL) + onError，且只收口一次
        if (error instanceof CancelException) {
            handleCancel(currentStep, error.getMessage() == null ? "任务已被用户取消" : error.getMessage());
            return;
        }

        log.fine(
                "ReActTokenStream 错误 executionId="
                        + chatContext.getExecutionId()
                        + ", step="
                        + currentStep
                        + ", error="
                        + (error == null ? null : error.getMessage()));

        boolean maxSteps = isMaxStepsError(error);
        if (maxSteps) {
            finishExecution(AgentExecutionStatus.COMPLETED, AgentRunState.MAX_STEPS, null);
        } else {
            finishExecution(
                    AgentExecutionStatus.FAILED,
                    AgentRunState.MODEL_CALL_ERROR,
                    error == null ? null : error.getMessage());
        }
        if (middlewareManager != null) {
            middlewareManager.triggerOnLoopError(currentStep, error, chatContext);
            if (maxSteps) {
                middlewareManager.triggerOnStop(
                        currentStep,
                        stopResult(StopResultState.MAX_STEPS, error.getMessage()),
                        chatContext);
            } else {
                middlewareManager.triggerOnStopWithError(currentStep, error, chatContext);
            }
        }
        notifyError(error);
    }

    // onLoopError + onStop 成对触发
    private void notifyStop(
            int currentStep, StopResultState state, String runRes, Throwable error) {
        if (middlewareManager == null) {
            return;
        }
        middlewareManager.triggerOnLoopError(currentStep, error, chatContext);
        middlewareManager.triggerOnStop(currentStep, stopResult(state, runRes), chatContext);
    }

    private void notifyError(Throwable error) {
        if (errorHandler != null) {
            errorHandler.accept(error);
            return;
        }
        if (ignoreErrors) {
            return;
        }
        throw new AgentException("Agent流式执行失败: " + error.getMessage(), error);
    }

    private boolean isMaxStepsError(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause.getMessage() != null && cause.getMessage().startsWith(MAX_STEPS_PREFIX)) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static StopResult stopResult(StopResultState state, String runRes) {
        StopResult.Builder builder = StopResult.builder().stopResultState(state);
        return state == StopResultState.NORMAL
                ? builder.result(runRes).build()
                : builder.reason(runRes).build();
    }

    private boolean isCancelled() {
        return runControl != null && runControl.isCancellationRequested();
    }

    private boolean acceptCallback() {
        return runControl == null
                || (runRegistry != null
                        && runRegistry.isCurrent(
                                chatContext.getMemoryId(), chatContext.getExecutionId())
                        && !runControl.isTerminal()
                        && !runControl.isCancellationRequested());
    }

    private void finishExecution(
            AgentExecutionStatus status, AgentRunState resultState, String errorMessage) {
        if (runControl == null || !finalized.compareAndSet(false, true)) return;
        runControl.finalizing();
        runControl.finish(status, resultState, errorMessage);
        if (runRegistry != null) runRegistry.complete(runControl);
        log.fine(
                "ReActTokenStream 收口 executionId="
                        + chatContext.getExecutionId()
                        + ", status="
                        + status
                        + ", resultState="
                        + resultState);
    }
}
