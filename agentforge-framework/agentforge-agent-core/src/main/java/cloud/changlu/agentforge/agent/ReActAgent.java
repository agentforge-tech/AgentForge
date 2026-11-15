package cloud.changlu.agentforge.agent;

import cloud.changlu.agentforge.agent.component.middleware.AgentMiddlewareManager;
import cloud.changlu.agentforge.agent.component.middleware.IAgentMiddleware;
import cloud.changlu.agentforge.agent.component.state.compensation.MessageChainRepairer;
import cloud.changlu.agentforge.agent.component.state.control.AgentRunControl;
import cloud.changlu.agentforge.agent.constant.AgentPromptConstants;
import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.domain.AgentRequest;
import cloud.changlu.agentforge.agent.domain.AgentRunState;
import cloud.changlu.agentforge.agent.domain.AgentSettings;
import cloud.changlu.agentforge.agent.domain.ThinkResult;
import cloud.changlu.agentforge.agent.exception.AgentException;
import cloud.changlu.agentforge.agent.exception.CancelException;
import cloud.changlu.agentforge.agent.governance.PermissionInterruptModule;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.AgentInterruptSnapshot;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.AgentSuspendInfo;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.ApprovalAction;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptBatchResumeCommand;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptRequest;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptResponse;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptResumeContext;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptType;
import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionDenial;
import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionPreflightResult;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserResponse;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserSupport;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserTool;
import cloud.changlu.agentforge.agent.memory.ChatMemory;
import cloud.changlu.agentforge.agent.memory.ChatMemoryProvider;
import cloud.changlu.agentforge.agent.retry.AiCallRetrySupport;
import cloud.changlu.agentforge.agent.step.ChatResult;
import cloud.changlu.agentforge.agent.step.StepResult;
import cloud.changlu.agentforge.agent.stream.TokenStream;
import cloud.changlu.agentforge.agent.tool.AgentToolExecutor;
import cloud.changlu.agentforge.model.chat.ChatCall;
import cloud.changlu.agentforge.model.chat.ChatModel;
import cloud.changlu.agentforge.model.chat.StreamingChatModel;
import cloud.changlu.agentforge.model.chat.message.AiMessage;
import cloud.changlu.agentforge.model.chat.message.ChatMessage;
import cloud.changlu.agentforge.model.chat.message.ChatMessageType;
import cloud.changlu.agentforge.model.chat.message.SystemMessage;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionResultMessage;
import cloud.changlu.agentforge.model.chat.request.ChatRequest;
import cloud.changlu.agentforge.model.chat.request.DefaultChatRequestParameters;
import cloud.changlu.agentforge.model.chat.response.ChatResponse;
import cloud.changlu.agentforge.model.exception.ModelException;
import cloud.changlu.agentforge.model.tool.ToolExecutor;
import cloud.changlu.agentforge.model.tool.execution.ToolService;
import cloud.changlu.agentforge.model.tool.spec.ToolSpecification;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * @description ReAct Agent：一轮think（模型推理是否要调用工具）+ 一轮act（执行工具并写回记忆），循环到结束或步长上限
 * @author changlu
 * @date 2026/9/16
 */
public class ReActAgent extends BaseReActAgent implements IInterruptibleAgent {

    private static final Logger log = Logger.getLogger(ReActAgent.class.getName());

    private final String agentName;
    private final String description;
    private final String systemPrompt;

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final ChatMemoryProvider chatMemoryProvider;
    private final AgentSettings agentSettings;
    private final ToolService toolService;
    private final AgentToolExecutor toolExecutor;
    private final AgentMiddlewareManager middlewareManager;
    private final PermissionInterruptModule permissionInterruptModule;

    protected ReActAgent(ReActAgentBuilder builder) {
        this.agentName = builder.agentName;
        this.description = builder.description;

        this.chatModel = builder.chatModel;
        this.streamingChatModel = builder.streamingChatModel;
        this.chatMemoryProvider = builder.chatMemoryProvider;
        this.toolService = builder.toolService;
        this.permissionInterruptModule = builder.permissionInterruptModule;
        if (permissionInterruptModule != null && permissionInterruptModule.isAskUserEnabled()) {
            boolean registered = false;
            for (ToolSpecification specification : this.toolService.toolSpecifications()) {
                if (AskUserTool.TOOL_NAME.equals(specification.name())) {
                    registered = true;
                    break;
                }
            }
            if (!registered) this.toolService.tools(Arrays.<Object>asList(new AskUserTool()));
        }
        this.agentSettings =
                builder.agentSettings == null
                        ? AgentSettings.defaultSettings()
                        : builder.agentSettings;

        // 构建系统提示词：工具数量 > 1 且并发开关开启（默认开启）时，追加并发调用提示语
        this.systemPrompt =
                buildSystemPrompt(builder.systemPrompt, builder.toolService, this.agentSettings);

        this.toolExecutor = new AgentToolExecutor(builder.toolService);
        this.middlewareManager = new AgentMiddlewareManager();
        this.middlewareManager.registerAll(builder.middlewares);
    }

    /**
     * 构建最终的系统提示词。
     *
     * <p>并发调用提示的追加条件为「工具数量 &gt; 1」**且**「{@link AgentSettings#isEnableConcurrentToolExecution()}
     * 开关开启（默认开启）」，两者同时满足才会提示模型 可以在同一轮并发调用多个工具；任一条件不满足则保持原始提示词不变。
     *
     * @param basePrompt 调用方传入的原始系统提示词
     * @param toolService 工具服务，用于统计可并发的工具数量
     * @param agentSettings Agent 配置，用于读取并发执行开关
     * @return 可能追加了并发提示语后的系统提示词
     */
    private static String buildSystemPrompt(
            String basePrompt, ToolService toolService, AgentSettings agentSettings) {
        // 可并发的工具数量：仅当工具数量大于 1 时才存在并发调用的可能
        int toolCount = toolService == null ? 0 : toolService.toolSpecifications().size();
        // 并发开关：默认开启；关闭时即使工具数量大于 1 也不追加提示
        boolean concurrentEnabled =
                agentSettings != null && agentSettings.isEnableConcurrentToolExecution();

        if (toolCount > 1 && concurrentEnabled) {
            return (basePrompt == null ? "" : basePrompt)
                    + AgentPromptConstants.CONCURRENT_TOOL_EXECUTION_PROMPT;
        }
        return basePrompt;
    }

    public static ReActAgentBuilder builder() {
        return new ReActAgentBuilder();
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public String getDescription() {
        return description;
    }

    public String getAgentName() {
        return agentName;
    }

    @Override
    protected void initMessages(AgentChatContext chatContext) {
        rebuildSystemMessages(chatContext, false);
        super.initMessages(chatContext);
    }

    @Override
    protected void prepareMessagesForResume(AgentChatContext chatContext) {
        rebuildSystemMessages(chatContext, true);
    }

    private void rebuildSystemMessages(AgentChatContext chatContext, boolean stripSyntheticAi) {
        ChatMemory chatMemory = chatContext.getChatMemory();
        List<ChatMessage> messages = chatMemory.messages();

        // 过滤掉所有系统消息，保留其他类型的消息；恢复时额外剔除框架写入的合成 AiMessage
        List<ChatMessage> nonSystemMessages = new ArrayList<ChatMessage>();
        for (ChatMessage message : messages) {
            if (message.type() == ChatMessageType.SYSTEM) {
                continue;
            }
            if (stripSyntheticAi && MessageChainRepairer.isSyntheticAiMessage(message)) {
                continue;
            }
            nonSystemMessages.add(message);
        }

        // 重新构建消息列表：新系统消息 + 其他非系统消息
        List<ChatMessage> newMessages = new ArrayList<ChatMessage>();
        if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
            newMessages.add(SystemMessage.from(systemPrompt));
        }
        newMessages.addAll(nonSystemMessages);

        // 清空原有记忆并重新添加
        chatMemory.clear();
        for (ChatMessage message : newMessages) {
            chatMemory.add(message);
        }
    }

    @Override
    protected ThinkResult think(int currentStep, AgentChatContext chatContext) {
        AgentRunControl control = runControl(chatContext);
        if (control != null) control.modelCall(currentStep);
        ChatMemory chatMemory = chatContext.getChatMemory();
        List<ChatMessage> curCallMessages = chatMemory.messages();
        log.fine(
                "ReAct think 请求模型 executionId="
                        + chatContext.getExecutionId()
                        + ", step="
                        + currentStep
                        + ", messages="
                        + curCallMessages.size());

        int retryCount = getAgentSettings().getAiCallRetry();
        long aiCallRetryDelay = getAgentSettings().getAiCallRetryDelay();

        // 构建请求request对象：当前记忆消息 + 全部工具声明
        ChatRequest chatRequest =
                ChatRequest.builder()
                        .messages(curCallMessages)
                        .parameters(
                                DefaultChatRequestParameters.builder()
                                        .tools(
                                                new ArrayList<ToolSpecification>(
                                                        getToolSpecifications()))
                                        .build())
                        .build();

        // 触发模型调用前的中间件
        ChatRequest processedRequest =
                middlewareManager.triggerBeforeModelCall(currentStep, chatRequest, chatContext);
        if (processedRequest == null) {
            // 中间件中断本轮模型调用，直接以中断原因结束任务，不再继续消耗步长
            return ThinkResult.builder()
                    .isFinish(true)
                    .needAct(false)
                    .runRes("模型调用被中间件中断")
                    .curActTools(Collections.<ToolExecutionRequest>emptyList())
                    .build();
        }

        // real model call
        ChatResponse chatResponse;
        try {
            chatResponse =
                    AiCallRetrySupport.execute(
                            () -> {
                                // 进入调用前先确认没有取消，避免创建无意义的底层请求。
                                if (control != null && control.isCancellationRequested()) {
                                    throw new CancelException("模型调用已取消");
                                }
                                ChatCall call = getChatModel().newCall(processedRequest);
                                if (control != null) {
                                    // registerModelHandle 内部会处理“取消先到、句柄后到”的竞态，并立即 cancel。
                                    control.registerModelHandle(call);
                                    // 注册后可能刚好被取消，此时不再真正发起 execute。
                                    if (control.isCancellationRequested()) {
                                        throw new CancelException("模型调用已取消");
                                    }
                                }
                                return call.execute();
                            },
                            retryCount,
                            aiCallRetryDelay,
                            (attempt, maxRetries, lastException, delayMs) -> {
                                // 取消后不再触发重试回调和退避等待。
                                if (control != null && control.isCancellationRequested()) {
                                    throw new CancelException("模型调用已取消");
                                }
                                if (control != null) control.retryWait();
                                middlewareManager.triggerOnAiCallRetry(
                                        currentStep,
                                        processedRequest,
                                        chatContext,
                                        attempt,
                                        maxRetries,
                                        delayMs,
                                        lastException);
                            });
        } catch (Exception e) {
            if (control != null && control.isCancellationRequested()) {
                log.fine(
                        "ReAct think 模型调用已取消 executionId="
                                + chatContext.getExecutionId()
                                + ", step="
                                + currentStep);
                throw new CancelException("模型调用已取消", e);
            }
            // 触发模型调用错误的中间件
            middlewareManager.triggerOnModelCallError(
                    currentStep, processedRequest, e, chatContext);
            // 模型调用失败，直接结束任务，避免继续进行工具调用
            return ThinkResult.builder()
                    .state(AgentRunState.MODEL_CALL_ERROR)
                    .isFinish(true)
                    .needAct(false)
                    .runRes("模型调用失败，请解决请求异常: " + describeError(e))
                    .curActTools(Collections.<ToolExecutionRequest>emptyList())
                    .build();
        }

        // 取消可能在模型返回后才到达，迟到结果不能再写入记忆
        if (control != null && control.isCancellationRequested()) {
            throw new CancelException("模型调用已取消");
        }

        // 触发模型调用后的中间件
        chatResponse =
                middlewareManager.triggerAfterModelCall(
                        currentStep, processedRequest, chatResponse, chatContext);

        AiMessage aiMessage = chatResponse.aiMessage();
        log.fine(
                "ReAct think 收到模型响应 executionId="
                        + chatContext.getExecutionId()
                        + ", step="
                        + currentStep
                        + ", toolCalls="
                        + aiMessage.toolExecutionRequests().size());

        // 添加message消息
        addMessage(chatContext, aiMessage);

        List<ToolExecutionRequest> curActTools = new ArrayList<ToolExecutionRequest>();
        // 未开启stop tool，直接根据是否还有工具调用来判断任务是否结束
        boolean isFinish = aiMessage.toolExecutionRequests().isEmpty();
        String runRes = "";
        if (isFinish) {
            runRes = aiMessage.text();
        } else {
            curActTools = aiMessage.toolExecutionRequests();
        }

        return ThinkResult.builder()
                .needAct(!curActTools.isEmpty())
                .isFinish(isFinish)
                .runRes(runRes)
                .curActTools(curActTools)
                .build();
    }

    /** 模型调用异常时尽量带上服务端响应体，便于快速定位400/401这类协议级错误 */
    private static String describeError(Exception e) {
        if (e instanceof ModelException) {
            String body = ((ModelException) e).responseBody();
            if (body != null && !body.trim().isEmpty()) {
                return e.getMessage() + " | response: " + body.trim();
            }
        }
        return e.getMessage();
    }

    @Override
    protected StepResult act(
            int currentStep, List<ToolExecutionRequest> curActTools, AgentChatContext chatContext) {
        if (permissionInterruptModule != null) {
            PermissionPreflightResult preflight =
                    permissionInterruptModule.preflight(currentStep, curActTools, chatContext);
            if (preflight.isSuspended()) {
                AgentSuspendInfo suspendInfo = preflight.getSuspendInfo();
                chatContext.putExtension(PermissionInterruptModule.SUSPEND_INFO_KEY, suspendInfo);
                middlewareManager.triggerOnInterruptRequested(
                        currentStep, suspendInfo, chatContext);
                return StepResult.toSuspended("interrupt batch: " + suspendInfo.getBatchId());
            }
            if (preflight.hasDenials()) {
                return actWithDenials(
                        currentStep, curActTools, preflight.getDenials(), chatContext);
            }
        }
        AgentRunControl control = runControl(chatContext);
        if (control != null) control.beginTools(curActTools, currentStep);
        log.fine(
                "ReAct act 开始执行工具 executionId="
                        + chatContext.getExecutionId()
                        + ", step="
                        + currentStep
                        + ", tools="
                        + (curActTools == null ? 0 : curActTools.size()));
        List<ToolExecutionResultMessage> toolMessages =
                getToolExecutor()
                        .execute(
                                currentStep,
                                curActTools,
                                chatContext,
                                null,
                                getMiddlewareManager(),
                                getAgentSettings().isEnableConcurrentToolExecution(),
                                control);
        return StepResult.toFinished(toolMessages.toString());
    }

    /**
     * 存在直接 DENY 时按原 Tool Call 顺序执行：拒绝项写失败 Tool Result，其余项正常执行。
     *
     * <p>该路径会牺牲并发执行，换取结果与原始调用顺序严格一致；没有 DENY 时仍走原有并发批次。
     */
    private StepResult actWithDenials(
            int currentStep,
            List<ToolExecutionRequest> requests,
            List<PermissionDenial> denials,
            AgentChatContext chatContext) {
        AgentRunControl control = runControl(chatContext);
        if (control != null) control.beginTools(requests, currentStep);
        Map<String, PermissionDenial> deniedById = new LinkedHashMap<String, PermissionDenial>();
        for (PermissionDenial denial : denials) {
            deniedById.put(denial.getRequest().id(), denial);
        }
        for (ToolExecutionRequest request : requests) {
            if (control != null && control.isCancellationRequested()) {
                throw new CancelException("工具执行已取消");
            }
            PermissionDenial denial = deniedById.get(request.id());
            if (denial != null) {
                if (control != null) control.toolFailed(request.id());
                addMessage(chatContext, toolResult(request, denial.getReason(), true));
                continue;
            }
            if (control != null) control.toolRunning(request.id());
            List<ToolExecutionResultMessage> results =
                    toolExecutor.execute(
                            currentStep,
                            Collections.singletonList(request),
                            chatContext,
                            null,
                            middlewareManager,
                            false,
                            null);
            ToolExecutionResultMessage result =
                    results == null || results.isEmpty() ? null : results.get(0);
            if (control != null) {
                if (result != null && Boolean.TRUE.equals(result.isError())) {
                    control.toolFailed(request.id());
                } else {
                    control.toolCompleted(request.id());
                }
            }
        }
        return StepResult.toFinished("tool batch executed with permission denials");
    }

    @Override
    protected List<ToolSpecification> getToolSpecifications() {
        return toolService.toolSpecifications();
    }

    protected Map<String, ToolExecutor> getExecutorMap() {
        return toolService.toolExecutors();
    }

    /** 提交一组审批/回答，将冻结批次的工具结果按原始顺序写回记忆，再以非流式继续 ReAct。 */
    @Override
    public ChatResult resumeInterrupt(InterruptBatchResumeCommand command) {
        if (permissionInterruptModule == null) {
            throw new IllegalStateException("PermissionInterruptModule is not configured");
        }
        if (chatModel == null) {
            throw new AgentException("chatModel is null, need set");
        }
        AgentInterruptSnapshot snapshot = applyInterruptBatch(command);
        return resume(snapshot.getMemoryId(), snapshot.getSourceExecutionId());
    }

    /**
     * 提交一组审批/回答，将冻结批次的工具结果按原始顺序写回记忆，再以流式继续 ReAct。
     *
     * <p>冻结批次的工具恢复在调用本方法时同步执行，不经过返回的 TokenStream 的 onToolExecuted 回调；返回的流式句柄只覆盖恢复后的 ReAct 轮次。
     */
    @Override
    public TokenStream resumeInterruptStream(InterruptBatchResumeCommand command) {
        if (permissionInterruptModule == null) {
            throw new IllegalStateException("PermissionInterruptModule is not configured");
        }
        if (streamingChatModel == null) {
            throw new AgentException("streamingChatModel is null, need set");
        }
        AgentInterruptSnapshot snapshot = applyInterruptBatch(command);
        return resumeStream(snapshot.getMemoryId(), snapshot.getSourceExecutionId());
    }

    /** 校验恢复指令并将冻结批次的工具结果按原始顺序写回 ChatMemory。 */
    private AgentInterruptSnapshot applyInterruptBatch(InterruptBatchResumeCommand command) {
        PermissionInterruptModule.ResolvedInterruptBatch resolved =
                permissionInterruptModule.resolve(command);
        AgentInterruptSnapshot snapshot = resolved.getSnapshot();
        ChatMemory memory = chatMemoryProvider.get(snapshot.getMemoryId());

        AgentRequest request = new AgentRequest();
        request.setMemoryId(snapshot.getMemoryId());
        request.setExecutionId(snapshot.getSourceExecutionId());
        request.setQuestion("恢复中断");
        AgentChatContext context =
                AgentChatContext.builder()
                        .executionId(snapshot.getSourceExecutionId())
                        .request(request)
                        .chatMemory(memory)
                        .chatModel(chatModel)
                        .build();

        InterruptResumeContext resumeContext =
                new InterruptResumeContext(snapshot, resolved.getResponses());
        middlewareManager.triggerOnBeforeInterruptResume(resumeContext, context);
        try {
            for (ToolExecutionRequest toolRequest : snapshot.getToolRequests()) {
                PermissionDenial denial = findDenial(snapshot, toolRequest.id());
                if (denial != null) {
                    memory.add(toolResult(toolRequest, denial.getReason(), true));
                    continue;
                }
                InterruptRequest interrupt = findInterrupt(snapshot, toolRequest.id());
                if (interrupt == null) {
                    executeFrozenTool(snapshot, context, toolRequest);
                    continue;
                }
                InterruptResponse response = resolved.responseOf(interrupt);
                if (interrupt.getType() == InterruptType.ASK_USER) {
                    AskUserResponse answer = response.getAskUserResponse();
                    memory.add(
                            toolResult(
                                    toolRequest,
                                    AskUserSupport.renderToolResult(
                                            interrupt.getAskUserRequest(),
                                            answer,
                                            interrupt.getInterruptId()),
                                    false));
                } else if (response.getAction() == ApprovalAction.REJECT) {
                    memory.add(toolResult(toolRequest, "User rejected this tool call", true));
                } else {
                    executeFrozenTool(snapshot, context, toolRequest);
                }
            }
            permissionInterruptModule.complete(resolved);
            middlewareManager.triggerOnAfterInterruptResume(resumeContext, context);
        } catch (RuntimeException e) {
            middlewareManager.triggerOnInterruptResumeError(resumeContext, e, context);
            throw e;
        }
        return snapshot;
    }

    private void executeFrozenTool(
            AgentInterruptSnapshot snapshot,
            AgentChatContext context,
            ToolExecutionRequest request) {
        toolExecutor.execute(
                snapshot.getStep(),
                Collections.singletonList(request),
                context,
                null,
                middlewareManager,
                false,
                null);
    }

    private static InterruptRequest findInterrupt(
            AgentInterruptSnapshot snapshot, String toolCallId) {
        for (InterruptRequest interrupt : snapshot.getInterrupts()) {
            if (interrupt.getToolRequest().id().equals(toolCallId)) return interrupt;
        }
        return null;
    }

    private static PermissionDenial findDenial(AgentInterruptSnapshot snapshot, String toolCallId) {
        for (PermissionDenial denial : snapshot.getDenials()) {
            if (denial.getRequest().id().equals(toolCallId)) return denial;
        }
        return null;
    }

    private static ToolExecutionResultMessage toolResult(
            ToolExecutionRequest request, String text, boolean error) {
        return ToolExecutionResultMessage.builder()
                .id(request.id())
                .toolName(request.name())
                .text(text == null ? "" : text)
                .isError(error)
                .build();
    }

    protected List<ChatMessage> getAllMessages(Object memoryId) {
        return chatMemoryProvider.get(memoryId).messages();
    }

    @Override
    public ChatModel getChatModel() {
        return chatModel;
    }

    @Override
    protected StreamingChatModel getStreamChatModel() {
        return streamingChatModel;
    }

    @Override
    public ChatMemoryProvider getChatMemoryProvider() {
        return chatMemoryProvider;
    }

    @Override
    protected AgentSettings getAgentSettings() {
        return agentSettings;
    }

    @Override
    protected ToolService getToolService() {
        return toolService;
    }

    @Override
    protected AgentToolExecutor getToolExecutor() {
        return toolExecutor;
    }

    @Override
    protected AgentMiddlewareManager getMiddlewareManager() {
        return middlewareManager;
    }

    @Override
    protected PermissionInterruptModule getPermissionInterruptModule() {
        return permissionInterruptModule;
    }

    public static class ReActAgentBuilder {
        private String systemPrompt;
        private String description;
        private String agentName;

        private ChatModel chatModel;
        private StreamingChatModel streamingChatModel;
        private ChatMemoryProvider chatMemoryProvider;
        private ToolService toolService;
        private AgentSettings agentSettings;
        private PermissionInterruptModule permissionInterruptModule;
        private final List<IAgentMiddleware> middlewares = new ArrayList<IAgentMiddleware>();

        public ReActAgentBuilder chatModel(ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        public ReActAgentBuilder streamingChatModel(StreamingChatModel streamingChatModel) {
            this.streamingChatModel = streamingChatModel;
            return this;
        }

        public ReActAgentBuilder chatMemoryProvider(ChatMemoryProvider chatMemoryProvider) {
            this.chatMemoryProvider = chatMemoryProvider;
            return this;
        }

        public ReActAgentBuilder toolService(ToolService toolService) {
            this.toolService = toolService;
            return this;
        }

        public ReActAgentBuilder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public ReActAgentBuilder description(String description) {
            this.description = description;
            return this;
        }

        public ReActAgentBuilder agentName(String agentName) {
            this.agentName = agentName;
            return this;
        }

        public ReActAgentBuilder agentSettings(AgentSettings agentSettings) {
            this.agentSettings = agentSettings;
            return this;
        }

        /** 启用权限与统一中断模块，run / runStream 均可挂起并通过 IInterruptibleAgent 恢复。 */
        public ReActAgentBuilder permissionInterruptModule(PermissionInterruptModule module) {
            this.permissionInterruptModule = module;
            return this;
        }

        /** 注册单个中间件，按注册顺序执行 */
        public ReActAgentBuilder middleware(IAgentMiddleware middleware) {
            if (middleware != null) {
                this.middlewares.add(middleware);
            }
            return this;
        }

        /** 批量注册中间件，按加入顺序执行 */
        public ReActAgentBuilder middlewares(List<IAgentMiddleware> middlewares) {
            if (middlewares != null) {
                for (IAgentMiddleware middleware : middlewares) {
                    middleware(middleware);
                }
            }
            return this;
        }

        public ReActAgent build() {
            return new ReActAgent(this);
        }
    }
}
