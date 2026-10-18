package cloud.changlu.agentforge.service.modules.agent.runtime.base;

import cloud.changlu.agentforge.agent.step.ChatResult;
import cloud.changlu.agentforge.service.modules.agent.domain.req.AgentChatReq;
import cloud.changlu.agentforge.service.modules.agent.runtime.context.AgentInitContext;

/** Agent 运行时管理器统一协议。 */
public interface IAgentManager<T, F> {

    Integer getType();

    Integer getMode();

    T createAndStoreAgent(String aid, String agentConfig, boolean isDebug);

    T createAndStoreAgent(AgentInitContext context);

    T getAgent(String aid, boolean isDebug);

    T getAgent(AgentInitContext context);

    T getAgentAndInitAgent(String aid, String agentConfig, boolean isDebug);

    T getAgentAndInitAgent(AgentInitContext context);

    void removeAgent(String aid, boolean isDebug);

    void removeAgent(String aid);

    ChatResult ask(T agent, AgentChatReq req);
}
