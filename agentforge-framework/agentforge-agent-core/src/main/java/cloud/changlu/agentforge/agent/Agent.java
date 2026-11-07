package cloud.changlu.agentforge.agent;

import cloud.changlu.agentforge.agent.component.middleware.AgentMiddlewareManager;
import cloud.changlu.agentforge.agent.component.state.compensation.CancellationMessageCompensator;
import cloud.changlu.agentforge.agent.component.state.compensation.MessageChainRepairer;
import cloud.changlu.agentforge.agent.component.state.control.AgentRunControl;
import cloud.changlu.agentforge.agent.component.state.domain.AgentExecutionPhase;
import cloud.changlu.agentforge.agent.component.state.domain.AgentExecutionState;
import cloud.changlu.agentforge.agent.component.state.domain.AgentExecutionStatus;
import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.domain.AgentRequest;
import cloud.changlu.agentforge.agent.domain.AgentRunState;
import cloud.changlu.agentforge.agent.domain.AgentSettings;
import cloud.changlu.agentforge.agent.domain.StopResult;
import cloud.changlu.agentforge.agent.exception.AgentException;
import cloud.changlu.agentforge.agent.exception.CancelException;
import cloud.changlu.agentforge.agent.memory.ChatMemory;
import cloud.changlu.agentforge.agent.memory.ChatMemoryProvider;
import cloud.changlu.agentforge.agent.step.ChatResult;
import cloud.changlu.agentforge.agent.step.StepResult;
import cloud.changlu.agentforge.agent.step.StepState;
import cloud.changlu.agentforge.agent.step.StopResultState;
import cloud.changlu.agentforge.agent.stream.CompletedTokenStream;
import cloud.changlu.agentforge.agent.stream.TokenStream;
import cloud.changlu.agentforge.model.chat.message.AiMessage;
import cloud.changlu.agentforge.model.chat.message.ChatMessage;
import cloud.changlu.agentforge.model.chat.message.ChatMessageType;
import cloud.changlu.agentforge.model.tool.execution.ToolService;

import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * @description Agent抽象类，定义了Agent的核心运行逻辑和步骤
 * @author changlu
 * @date 2026/9/16
 */
public abstract class Agent extends BaseAgent implements IResumableAgent {

    private static final Logger log = Logger.getLogger(Agent.class.getName());

    protected abstract ToolService getToolService();

    protected abstract AgentSettings getAgentSettings();

    protected abstract StepResult step(int currentStep, AgentChatContext chatContext)
            throws CancelException;

    protected abstract TokenStream stepStream(
            AgentChatContext chatContext, AgentRunControl control);

    protected abstract AgentMiddlewareManager getMiddlewareManager();

    /**
     * 针对一个目标结果来进行执行任务
     *
     * @param request AgentRequest
     * @return ChatResult
     */
    @Override
    public ChatResult run(AgentRequest request) {
        AgentChatContext chatContext = init(request);
        validateRunParams();
        AgentRunControl runControl = registerRun(chatContext);
        runControl.registerExecutionThread(Thread.currentThread());
        log.fine(
                "Agent run 启动 executionId="
                        + chatContext.getExecutionId()
                        + ", memoryId="
                        + chatContext.getMemoryId());
        return executeLoop(chatContext, runControl);
    }

    /**
     * 流式运行
     *
     * @param request AgentRequest
     * @return TokenStream
     */
    @Override
    public TokenStream runStream(AgentRequest request) {
        AgentChatContext chatContext = init(request);
        validateRunStreamParams();
        AgentRunControl control = registerRun(chatContext);
        log.fine(
                "Agent runStream 创建 executionId="
                        + chatContext.getExecutionId()
                        + ", memoryId="
                        + chatContext.getMemoryId());
        return stepStream(chatContext, control);
    }

    /**
     * 非流式恢复：从 (memoryId, executionId) 对应的历史继续执行。
     *
     * <p>核心步骤：
     *
     * <ol>
     *   <li>校验并准备恢复上下文（prepareResume）：确认无活跃执行、读取 ChatMemory、修复消息链；
     *   <li>如果历史已经是完整回答，直接返回既有结果，不重复调用模型；
     *   <li>否则注册新的执行记录和新的 executionId，登记执行线程；
     *   <li>复用统一 executeLoop 继续 ReAct 循环，最终按普通运行收口。
     * </ol>
     */
    @Override
    public ChatResult resume(Object memoryId, String executionId) {
        log.fine("Agent resume 请求 memoryId=" + memoryId + ", previousExecutionId=" + executionId);

        // 步骤 1：校验来源、读取 ChatMemory、按需修复消息链，并生成恢复上下文。
        ResumePlan plan = prepareResume(memoryId, executionId);

        // 步骤 2：历史已经正常结束，直接返回既有回答，不产生新的模型调用。
        if (plan.completed) {
            log.fine("Agent resume 命中已结束历史，直接返回 executionId=" + plan.executionId);
            finishCompletedResume(memoryId, plan.executionId);
            return ChatResult.toFinished(
                    plan.executionId, plan.question, plan.text, AgentRunState.SUCCESS);
        }

        // 步骤 3：注册新的运行记录；executionId 已在 prepareResume 中重新生成。
        validateRunParams();
        AgentRunControl control = registerRun(plan.context);
        control.registerExecutionThread(Thread.currentThread());
        log.fine(
                "Agent resume 继续执行 newExecutionId="
                        + plan.executionId
                        + ", previousExecutionId="
                        + executionId);

        // 步骤 4：复用普通运行循环，恢复不再追加 UserMessage。
        return executeLoop(plan.context, control);
    }

    /**
     * 流式恢复：从 (memoryId, executionId) 对应的历史继续执行。
     *
     * <p>与非流式恢复的差异只在返回形态：
     *
     * <ul>
     *   <li>历史已结束：返回 CompletedTokenStream，start() 时直接下发既有回答；
     *   <li>需要继续：注册新执行后返回 ReActTokenStream，由 start() 驱动后续循环。
     * </ul>
     */
    @Override
    public TokenStream resumeStream(Object memoryId, String executionId) {
        log.fine(
                "Agent resumeStream 请求 memoryId="
                        + memoryId
                        + ", previousExecutionId="
                        + executionId);

        // 步骤 1：与 resume 共用同一套校验、消息修复和上下文准备。
        ResumePlan plan = prepareResume(memoryId, executionId);

        // 步骤 2：历史已结束，返回一次性完成的 TokenStream。
        if (plan.completed) {
            log.fine("Agent resumeStream 命中已结束历史，直接返回 executionId=" + plan.executionId);
            finishCompletedResume(memoryId, plan.executionId);
            return new CompletedTokenStream(plan.executionId, plan.text);
        }

        // 步骤 3：注册新执行，返回流式句柄；真正的模型调用在 start() 中发生。
        validateRunStreamParams();
        AgentRunControl control = registerRun(plan.context);
        log.fine(
                "Agent resumeStream 继续执行 newExecutionId="
                        + plan.executionId
                        + ", previousExecutionId="
                        + executionId);
        return stepStream(plan.context, control);
    }

    private ChatResult executeLoop(AgentChatContext chatContext, AgentRunControl runControl) {
        String question = chatContext.getQuestion();
        AgentMiddlewareManager middlewareManager = getMiddlewareManager();

        // 触发中间件初始化完成回调（只执行一次）
        if (middlewareManager != null) {
            middlewareManager.triggerOnInitComplete(chatContext);
        }

        int currentStep = 1;
        // 获取到最大步数限制
        int maxSteps = getAgentSettings().getMaxSteps();

        // 本次运行的最终结果
        String runRes = "";
        String cancelReason = null;
        boolean isFinished = false;
        AgentRunState runState = null;

        try {
            StepResult curStep = StepResult.toRunning();
            while (currentStep <= maxSteps) {
                log.fine(
                        "Agent step 开始 executionId="
                                + chatContext.getExecutionId()
                                + ", step="
                                + currentStep
                                + "/"
                                + maxSteps);
                runControl.running(AgentExecutionPhase.BEFORE_LOOP, currentStep);
                if (middlewareManager != null) {
                    middlewareManager.triggerBeforeLoop(currentStep, chatContext);
                }
                curStep = step(currentStep, chatContext);
                log.fine(
                        "Agent step 结束 executionId="
                                + chatContext.getExecutionId()
                                + ", step="
                                + currentStep
                                + ", state="
                                + curStep.getState()
                                + ", runState="
                                + curStep.getRunState());
                if (middlewareManager != null) {
                    middlewareManager.triggerAfterLoop(currentStep, curStep, chatContext);
                }

                if (curStep.getState().equals(StepState.STOP)) {
                    isFinished = true;
                    runRes = curStep.getRes();
                    runState =
                            curStep.getRunState() == null
                                    ? AgentRunState.SUCCESS
                                    : curStep.getRunState();
                    break;
                }
                if (curStep.getState() == StepState.FINISHED) {
                    runControl.betweenSteps(currentStep);
                }
                currentStep++;
            }
        } catch (CancelException e) {
            Thread.interrupted();
            cancelReason = resolveCancelReason(runControl, "任务已被用户取消");
            runState = AgentRunState.CANCEL;
            log.fine(
                    "Agent 取消 executionId="
                            + chatContext.getExecutionId()
                            + ", step="
                            + currentStep
                            + ", reason="
                            + cancelReason);
            if (middlewareManager != null) {
                middlewareManager.triggerOnLoopError(currentStep, e, chatContext);
            }
        } catch (Exception e) {
            if (runControl.isCancellationRequested() || isInterruptionRelated(e)) {
                Thread.interrupted();
                cancelReason = resolveCancelReason(runControl, "任务已被中断");
                runState = AgentRunState.CANCEL;
                log.fine(
                        "Agent 中断按取消处理 executionId="
                                + chatContext.getExecutionId()
                                + ", step="
                                + currentStep
                                + ", error="
                                + e.getMessage());
                // 中断相关异常按取消处理，停止回调统一走下面的 cancelReason 分支，避免重复触发
                if (middlewareManager != null) {
                    middlewareManager.triggerOnLoopError(currentStep, e, chatContext);
                }
            } else {
                // 非中断相关的异常，先通知中间件再向外抛出
                log.fine(
                        "Agent 异常终止 executionId="
                                + chatContext.getExecutionId()
                                + ", step="
                                + currentStep
                                + ", error="
                                + e.getMessage());
                if (middlewareManager != null) {
                    middlewareManager.triggerOnLoopError(currentStep, e, chatContext);
                    middlewareManager.triggerOnStopWithError(currentStep, e, chatContext);
                }
                runControl.finalizing();
                runControl.finish(
                        AgentExecutionStatus.FAILED,
                        AgentRunState.MODEL_CALL_ERROR,
                        e.getMessage());
                runRegistry.complete(runControl);
                throw new AgentException("Agent执行失败: " + e.getMessage(), e);
            }
        }

        // 取消可能发生在循环自然结束的瞬间，这里补一次判定，避免误报完成
        if (cancelReason == null && runControl.isCancellationRequested()) {
            cancelReason = resolveCancelReason(runControl, "任务已被用户取消");
            runState = AgentRunState.CANCEL;
        }

        // 判断是取消中断还是步长中断情况
        if (cancelReason != null) {
            runControl.compensating();
            CancellationMessageCompensator.compensate(chatContext, cancelReason);
            runRes = cancelReason;
            runState = AgentRunState.CANCEL;
            if (middlewareManager != null) {
                middlewareManager.triggerOnStop(
                        currentStep, stopResult(StopResultState.CANCEL, runRes), chatContext);
            }
        } else if (!isFinished && currentStep > maxSteps) {
            runRes = "执行步长达到限制，请重新询问你的问题！";
            runState = AgentRunState.MAX_STEPS;
            if (middlewareManager != null) {
                middlewareManager.triggerOnStop(
                        currentStep, stopResult(StopResultState.MAX_STEPS, runRes), chatContext);
            }
        } else if (isFinished) {
            // 正常结束（模型不再要求调用工具）
            if (middlewareManager != null) {
                middlewareManager.triggerOnStop(
                        currentStep, stopResult(StopResultState.NORMAL, runRes), chatContext);
            }
        }

        // 结束状态映射：取消 -> CANCELLED，模型错误 -> FAILED，其余（成功/步长上限）-> COMPLETED
        AgentExecutionStatus executionStatus;
        if (runState == AgentRunState.CANCEL) {
            executionStatus = AgentExecutionStatus.CANCELLED;
        } else if (runState == AgentRunState.MODEL_CALL_ERROR) {
            executionStatus = AgentExecutionStatus.FAILED;
        } else {
            executionStatus = AgentExecutionStatus.COMPLETED;
        }
        runControl.finalizing();
        runControl.finish(
                executionStatus, runState == null ? AgentRunState.SUCCESS : runState, null);
        runRegistry.complete(runControl);
        log.fine(
                "Agent 运行结束 executionId="
                        + chatContext.getExecutionId()
                        + ", status="
                        + executionStatus
                        + ", runState="
                        + (runState == null ? AgentRunState.SUCCESS : runState)
                        + ", step="
                        + currentStep);

        return ChatResult.toFinished(
                chatContext.getExecutionId(),
                question,
                runRes,
                runState == null ? AgentRunState.SUCCESS : runState);
    }

    /**
     * 恢复前的统一准备：校验、读取历史、判断是否已结束、修复消息链并构建恢复上下文。
     *
     * <p>这里不注册运行记录，也不调用模型；它只产出 {@link ResumePlan}，由 resume/resumeStream 决定后续动作。
     */
    private ResumePlan prepareResume(Object memoryId, String executionId) {
        // 步骤 1：参数校验。恢复必须精确定位到某次来源执行。
        if (memoryId == null) {
            throw new AgentException("memoryId must not be null");
        }
        if (executionId == null || executionId.trim().isEmpty()) {
            throw new AgentException("executionId must not be blank");
        }

        // 步骤 2：并发保护。同一 memoryId 已有活跃执行时不允许恢复，避免两个循环同时写 ChatMemory。
        AgentRunControl active = runRegistry.current(memoryId);
        if (active != null && !active.isTerminal()) {
            throw new AgentException("会话存在正在运行的执行，无法恢复: " + memoryId);
        }

        // 步骤 2.1：如果来源执行快照仍可读到，则它必须已经终态；重启后 store 为空时跳过该校验。
        Optional<AgentExecutionState> source = runRegistry.snapshot(memoryId, executionId);
        if (source.isPresent() && !isTerminalStatus(source.get().getStatus())) {
            throw new AgentException("来源执行尚未结束，无法恢复: " + executionId);
        }

        // 步骤 3：读取来源会话的持久化历史；没有历史就无从恢复。
        ChatMemoryProvider provider = getChatMemoryProvider();
        if (provider == null) {
            throw new AgentException("ChatMemoryProvider is null, need set");
        }
        ChatMemory chatMemory = provider.get(memoryId);
        List<ChatMessage> messages = chatMemory.messages();
        if (messages == null || messages.isEmpty()) {
            throw new AgentException("会话没有可恢复的消息: " + memoryId);
        }

        // 步骤 4：判断历史是否已经完整结束。
        // 只有“真实 AI 回答且无工具调用”才算结束；
        // 取消补偿写入的 synthetic AiMessage 需要排除，否则会被误判为已完成。
        String question = lastUserText(messages);
        ChatMessage last = messages.get(messages.size() - 1);
        if (last.type() == ChatMessageType.AI
                && !((AiMessage) last).hasToolExecutionRequests()
                && !MessageChainRepairer.isSyntheticAiMessage(last)) {
            String completedExecutionId = createExecutionId();
            log.fine(
                    "prepareResume 命中完整历史 memoryId="
                            + memoryId
                            + ", previousExecutionId="
                            + executionId);
            return ResumePlan.completed(completedExecutionId, question, last.text());
        }

        // 步骤 5：防御性修复消息链。
        // 正常 runState 恢复不应出现悬空 tool call；这里兜底补齐 synthetic 工具结果。
        boolean repaired = MessageChainRepairer.repairDanglingToolCalls(chatMemory, executionId);
        if (repaired) {
            log.fine("prepareResume 修复悬空工具调用 previousExecutionId=" + executionId);
        }

        // 步骤 6：构建恢复上下文。
        // 关键点：生成全新的 executionId，并显式记录来源执行，后续取消/查询都使用新 ID。
        AgentRequest resumeRequest = new AgentRequest();
        resumeRequest.setMemoryId(memoryId);
        resumeRequest.setExecutionId(createExecutionId());
        resumeRequest.setQuestion(
                question == null || question.trim().isEmpty() ? "继续执行" : question);
        AgentChatContext context = initParams(resumeRequest);
        context.putExtension("agentforge.resume", Boolean.TRUE);
        context.putExtension("agentforge.resumeSource", "RUN_STATE");
        context.putExtension("agentforge.previousExecutionId", executionId);

        // 步骤 7：刷新系统消息，并剔除取消补偿写入的 synthetic AiMessage。
        // 这样恢复后的模型看到的是真实历史，而不是“本次执行已取消”的通知。
        prepareMessagesForResume(context);
        return ResumePlan.run(context, question);
    }

    /** 已结束历史的恢复：登记一个立即完成的执行，保证新 executionId 也能查询到终态。 */
    private void finishCompletedResume(Object memoryId, String executionId) {
        AgentRunControl control = runRegistry.register(executionId, memoryId);
        control.finish(AgentExecutionStatus.COMPLETED, AgentRunState.SUCCESS, null);
        runRegistry.complete(control);
    }

    private static String resolveCancelReason(AgentRunControl control, String fallback) {
        String reason = control == null ? null : control.getCancelReason();
        return reason == null || reason.trim().isEmpty() ? fallback : reason;
    }

    private static boolean isTerminalStatus(AgentExecutionStatus status) {
        return status == AgentExecutionStatus.COMPLETED
                || status == AgentExecutionStatus.CANCELLED
                || status == AgentExecutionStatus.FAILED;
    }

    private static String lastUserText(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).type() == ChatMessageType.USER) {
                return messages.get(i).text();
            }
        }
        return null;
    }

    private void validateRunParams() {
        if (getChatModel() == null) {
            throw new AgentException("chatModel is null, need set");
        }
        if (getChatMemoryProvider() == null) {
            throw new AgentException("ChatMemoryProvider is null, need set");
        }
        if (getToolService() == null) {
            throw new AgentException("toolService is null, need set");
        }
    }

    private void validateRunStreamParams() {
        ChatMemoryProvider chatMemoryProvider = getChatMemoryProvider();
        if (chatMemoryProvider == null) {
            throw new AgentException("ChatMemoryProvider is null, need set");
        }
    }

    // 判断异常是否与线程中断相关，中断统一按取消处理而不向外抛出
    private boolean isInterruptionRelated(Exception e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof InterruptedException) {
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

    private static final class ResumePlan {
        private final boolean completed;
        private final String executionId;
        private final String question;
        private final String text;
        private final AgentChatContext context;

        private ResumePlan(
                boolean completed,
                String executionId,
                String question,
                String text,
                AgentChatContext context) {
            this.completed = completed;
            this.executionId = executionId;
            this.question = question;
            this.text = text;
            this.context = context;
        }

        private static ResumePlan completed(String executionId, String question, String text) {
            return new ResumePlan(true, executionId, question, text, null);
        }

        private static ResumePlan run(AgentChatContext context, String question) {
            return new ResumePlan(false, context.getExecutionId(), question, null, context);
        }
    }
}
