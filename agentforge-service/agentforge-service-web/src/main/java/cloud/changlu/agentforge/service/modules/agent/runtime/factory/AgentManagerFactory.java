package cloud.changlu.agentforge.service.modules.agent.runtime.factory;

import cloud.changlu.agentforge.service.modules.agent.runtime.base.IAgentManager;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 按 Agent type + mode 分发运行时管理器。 */
@Component
public class AgentManagerFactory {

    private final Map<String, IAgentManager<?, ?>> managers = new HashMap<>();

    public AgentManagerFactory(List<IAgentManager<?, ?>> agentManagers) {
        for (IAgentManager<?, ?> agentManager : agentManagers) {
            managers.put(
                    mergeTypeAndMode(agentManager.getType(), agentManager.getMode()), agentManager);
        }
    }

    @SuppressWarnings("unchecked")
    public <T, F> IAgentManager<T, F> getAgentManagerByTypeAndMode(Integer type, Integer mode) {
        int actualMode = mode == null ? 0 : mode;
        IAgentManager<?, ?> manager = managers.get(mergeTypeAndMode(type, actualMode));
        if (manager == null) {
            throw new IllegalArgumentException(
                    "No AgentManager found for type: " + type + ", mode: " + actualMode);
        }
        return (IAgentManager<T, F>) manager;
    }

    private String mergeTypeAndMode(Integer type, Integer mode) {
        return type + "_" + mode;
    }
}
