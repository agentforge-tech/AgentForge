package cloud.changlu.agentforge.agent;

import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptBatchResumeCommand;
import cloud.changlu.agentforge.agent.step.ChatResult;
import cloud.changlu.agentforge.agent.stream.TokenStream;

/**
 * 支持权限审核 / AskUser 中断挂起后，提交批量审批结果并恢复执行的 Agent。
 *
 * <p>与非中断恢复的 {@link IResumableAgent} 相比，恢复入口不再只依赖 (memoryId, executionId)，
 * 而是通过中断批次ID定位冻结的工具调用，并按原始顺序写回工具结果后继续 ReAct。
 */
public interface IInterruptibleAgent extends IResumableAgent {

    /**
     * 非流式中断恢复：提交一批审批/回答，应用冻结批次后继续执行。
     *
     * @param command 批量恢复指令
     * @return 恢复后的执行结果
     */
    ChatResult resumeInterrupt(InterruptBatchResumeCommand command);

    /**
     * 流式中断恢复：提交一批审批/回答，应用冻结批次后以流式方式继续执行。
     *
     * @param command 批量恢复指令
     * @return 恢复后的流式句柄
     */
    TokenStream resumeInterruptStream(InterruptBatchResumeCommand command);
}
