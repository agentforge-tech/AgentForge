package cloud.changlu.agentforge.agent;

import cloud.changlu.agentforge.agent.step.ChatResult;
import cloud.changlu.agentforge.agent.stream.TokenStream;

/**
 * 支持基于已持久化 ChatMemory 与 AgentRunState 恢复执行的 Agent。
 *
 * <p>恢复入口与 {@link IAgent#cancel(Object, String)} 对称，使用 memoryId + executionId 精确定位来源执行。
 */
public interface IResumableAgent extends IAgent {

    /**
     * 非流式恢复：从来源执行的 ChatMemory 继续执行。
     *
     * @param memoryId 会话ID
     * @param executionId 来源执行ID
     * @return 恢复后的执行结果，executionId 为本次新执行ID
     */
    ChatResult resume(Object memoryId, String executionId);

    /**
     * 流式恢复：从来源执行的 ChatMemory 继续执行。
     *
     * @param memoryId 会话ID
     * @param executionId 来源执行ID
     * @return 恢复后的流式句柄，executionId 为本次新执行ID
     */
    TokenStream resumeStream(Object memoryId, String executionId);
}
