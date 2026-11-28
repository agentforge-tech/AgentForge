package cloud.changlu.agentforge.service.modules.trace.middleware;

import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.extend.middlewares.trace.AgentTraceCollector;
import cloud.changlu.agentforge.agent.extend.middlewares.trace.AgentTraceMiddleware;
import cloud.changlu.agentforge.agent.extend.middlewares.trace.domain.AgentTraceSnapshot;
import cloud.changlu.agentforge.service.modules.agent.runtime.context.ServiceAgentContextAccessor;
import cloud.changlu.agentforge.service.modules.agent.runtime.context.ServiceAgentExecutionContext;
import cloud.changlu.agentforge.service.modules.trace.domain.AgentForgeTraceSnapshot;

import org.springframework.stereotype.Component;

import java.util.Map;

/** Adds AgentForge Service metadata without coupling Agent Core to business concepts. */
@Component
public class AgentForgeTraceMiddleware extends AgentTraceMiddleware {
    private final ServiceAgentContextAccessor contextAccessor;

    public AgentForgeTraceMiddleware(
            AgentTraceCollector collector, ServiceAgentContextAccessor contextAccessor) {
        super(collector);
        this.contextAccessor = contextAccessor;
    }

    @Override
    protected void enrichAttributes(AgentChatContext context, Map<String, Object> traceAttributes) {
        ServiceAgentExecutionContext executionContext = contextAccessor.read(context);
        if (executionContext.getAid() != null) {
            traceAttributes.put(ServiceAgentContextAccessor.AID, executionContext.getAid());
        }
        if (executionContext.getDebug() != null) {
            traceAttributes.put(ServiceAgentContextAccessor.DEBUG, executionContext.getDebug());
        }
    }

    @Override
    protected AgentTraceSnapshot createSnapshot() {
        return new AgentForgeTraceSnapshot();
    }

    @Override
    protected void enrichSnapshot(
            AgentTraceSnapshot snapshot, Map<String, Object> traceAttributes) {
        AgentForgeTraceSnapshot serviceSnapshot = (AgentForgeTraceSnapshot) snapshot;
        Object aid = traceAttributes.get(ServiceAgentContextAccessor.AID);
        Object debug = traceAttributes.get(ServiceAgentContextAccessor.DEBUG);
        serviceSnapshot.setAid(aid == null ? null : String.valueOf(aid));
        serviceSnapshot.setDebug(debug instanceof Boolean ? (Boolean) debug : null);
    }
}
