package cloud.changlu.agentforge.service.modules.plugins.http.service;

import cloud.changlu.agentforge.service.modules.agent.runtime.base.IAgentManager;
import cloud.changlu.agentforge.service.modules.agent.runtime.factory.AgentManagerFactory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** HTTP 插件变更后清理 Agent 运行时缓存，不额外维护 Agent-插件关联表。 */
@Service
public class HttpPluginAgentCacheService {
    private static final int STANDARD_AGENT_TYPE = 1;
    private final AgentManagerFactory managerFactory;

    public HttpPluginAgentCacheService(AgentManagerFactory managerFactory) {
        this.managerFactory = managerFactory;
    }

    public void evictAllAfterCommit() {
        Runnable callback =
                () -> {
                    IAgentManager<Object, Object> manager =
                            managerFactory.getAgentManagerByType(STANDARD_AGENT_TYPE);
                    manager.clearAgents();
                };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            callback.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        callback.run();
                    }
                });
    }
}
