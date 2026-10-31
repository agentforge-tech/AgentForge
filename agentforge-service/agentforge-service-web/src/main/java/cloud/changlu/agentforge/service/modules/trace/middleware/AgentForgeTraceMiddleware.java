package cloud.changlu.agentforge.service.modules.trace.middleware;

import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.trace.AgentTraceCollector;
import cloud.changlu.agentforge.agent.trace.AgentTraceMiddleware;
import cloud.changlu.agentforge.agent.trace.domain.AgentTraceSnapshot;
import cloud.changlu.agentforge.service.modules.trace.AgentTraceAttributes;
import cloud.changlu.agentforge.service.modules.trace.domain.AgentForgeTraceSnapshot;

import org.springframework.stereotype.Component;

import java.util.Map;

/** Adds AgentForge Service metadata without coupling Agent Core to business concepts. */
@Component
public class AgentForgeTraceMiddleware extends AgentTraceMiddleware {

    public AgentForgeTraceMiddleware(AgentTraceCollector collector) {
        super(collector);
    }

    @Override
    protected void enrichAttributes(AgentChatContext context, Map<String, Object> traceAttributes) {
        copy(context, traceAttributes, AgentTraceAttributes.AID);
        copy(context, traceAttributes, AgentTraceAttributes.DEBUG);
    }

    @Override
    protected AgentTraceSnapshot createSnapshot() {
        return new AgentForgeTraceSnapshot();
    }

    @Override
    protected void enrichSnapshot(
            AgentTraceSnapshot snapshot, Map<String, Object> traceAttributes) {
        AgentForgeTraceSnapshot serviceSnapshot = (AgentForgeTraceSnapshot) snapshot;
        Object aid = traceAttributes.get(AgentTraceAttributes.AID);
        Object debug = traceAttributes.get(AgentTraceAttributes.DEBUG);
        serviceSnapshot.setAid(aid == null ? null : String.valueOf(aid));
        serviceSnapshot.setDebug(debug instanceof Boolean ? (Boolean) debug : null);
    }

    private void copy(
            AgentChatContext context, Map<String, Object> traceAttributes, String attributeName) {
        Object value = context.getExtension(attributeName);
        if (value != null) {
            traceAttributes.put(attributeName, value);
        }
    }
}
