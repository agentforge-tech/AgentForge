package cloud.changlu.agentforge.service.modules.agent.runtime.base;

import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptBatchResumeCommand;
import cloud.changlu.agentforge.agent.step.ChatResult;
import cloud.changlu.agentforge.agent.stream.TokenStream;
import cloud.changlu.agentforge.service.modules.agent.domain.req.AgentChatReq;
import cloud.changlu.agentforge.service.modules.agent.runtime.context.AgentInitContext;

/** Agent 运行时管理器统一协议。 */
public interface IAgentManager<T, F> {

    Integer getType();

    T createAndStoreAgent(String aid, String agentConfig, boolean isDebug);

    T createAndStoreAgent(AgentInitContext context);

    T getAgent(String aid, boolean isDebug);

    T getAgent(AgentInitContext context);

    T getAgentAndInitAgent(String aid, String agentConfig, boolean isDebug);

    T getAgentAndInitAgent(AgentInitContext context);

    void removeAgent(String aid, boolean isDebug);

    void removeAgent(String aid);

    void clearAgents();

    ChatResult ask(T agent, AgentChatReq req);

    TokenStream stream(T agent, AgentChatReq req);

    /** 恢复一次 HITL 中断；不支持的 Agent 类型应显式拒绝。 */
    default TokenStream resumeInterruptStream(T agent, InterruptBatchResumeCommand command) {
        throw new UnsupportedOperationException("HITL_NOT_SUPPORTED");
    }

    String createExecutionId(T agent);

    void cancel(T agent, String sessionId, String executionId);
}
