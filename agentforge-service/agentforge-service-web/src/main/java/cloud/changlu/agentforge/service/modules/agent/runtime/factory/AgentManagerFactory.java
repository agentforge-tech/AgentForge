package cloud.changlu.agentforge.service.modules.agent.runtime.factory;

import cloud.changlu.agentforge.service.modules.agent.runtime.base.IAgentManager;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 按 Agent type 分发运行时管理器。 */
@Component
public class AgentManagerFactory {

    private final Map<String, IAgentManager<?, ?>> managers = new HashMap<>();

    public AgentManagerFactory(List<IAgentManager<?, ?>> agentManagers) {
        for (IAgentManager<?, ?> agentManager : agentManagers) {
            managers.put(String.valueOf(agentManager.getType()), agentManager);
        }
    }

    @SuppressWarnings("unchecked")
    public <T, F> IAgentManager<T, F> getAgentManagerByType(Integer type) {
        IAgentManager<?, ?> manager = managers.get(String.valueOf(type));
        if (manager == null) {
            throw new IllegalArgumentException("No AgentManager found for type: " + type);
        }
        return (IAgentManager<T, F>) manager;
    }
}
