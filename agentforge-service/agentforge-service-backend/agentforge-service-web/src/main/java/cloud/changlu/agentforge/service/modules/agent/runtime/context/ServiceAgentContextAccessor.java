package cloud.changlu.agentforge.service.modules.agent.runtime.context;

import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.AgentInterruptSnapshot;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Service Agent 执行上下文的唯一键定义与存取入口。 */
@Component
public class ServiceAgentContextAccessor {
    public static final String USER_ID = "agentforge.execution.userId";
    public static final String AI_MESSAGE_ID = "agentforge.execution.aiMessageId";
    public static final String SESSION_ID = "agentforge.execution.sessionId";
    public static final String AID = "agentforge.execution.aid";
    public static final String DEBUG = "agentforge.execution.debug";

    public void write(Map<String, Object> extensions, ServiceAgentExecutionContext value) {
        if (extensions == null || value == null) return;
        extensions.put(USER_ID, value.getUserId());
        extensions.put(AI_MESSAGE_ID, value.getAiMessageId());
        extensions.put(SESSION_ID, value.getSessionId());
        extensions.put(AID, value.getAid());
        extensions.put(DEBUG, value.getDebug());
    }

    public ServiceAgentExecutionContext read(AgentChatContext context) {
        if (context == null) return new ServiceAgentExecutionContext(null, null, null, null, null);
        return new ServiceAgentExecutionContext(
                toLong(context.getExtension(USER_ID)),
                toLong(context.getExtension(AI_MESSAGE_ID)),
                toStringValue(context.getExtension(SESSION_ID)),
                toStringValue(context.getExtension(AID)),
                toBoolean(context.getExtension(DEBUG)));
    }

    public ServiceAgentExecutionContext read(AgentInterruptSnapshot snapshot) {
        Map<String, String> values = snapshot.getContextAttributes();
        return new ServiceAgentExecutionContext(
                toLong(values.get(USER_ID)),
                toLong(values.get(AI_MESSAGE_ID)),
                values.get(SESSION_ID),
                values.get(AID),
                toBoolean(values.get(DEBUG)));
    }

    public ServiceAgentExecutionContext requireForHitl(AgentChatContext context) {
        return requireForHitl(read(context));
    }

    public ServiceAgentExecutionContext requireForHitl(AgentInterruptSnapshot snapshot) {
        return requireForHitl(read(snapshot));
    }

    public Map<String, String> toSnapshotAttributes(ServiceAgentExecutionContext value) {
        ServiceAgentExecutionContext required = requireForHitl(value);
        Map<String, String> result = new LinkedHashMap<String, String>();
        result.put(USER_ID, String.valueOf(required.getUserId()));
        result.put(AI_MESSAGE_ID, String.valueOf(required.getAiMessageId()));
        result.put(SESSION_ID, required.getSessionId());
        result.put(AID, required.getAid());
        result.put(DEBUG, String.valueOf(required.getDebug()));
        return result;
    }

    private static ServiceAgentExecutionContext requireForHitl(ServiceAgentExecutionContext value) {
        require(value.getUserId(), USER_ID);
        require(value.getAiMessageId(), AI_MESSAGE_ID);
        require(value.getSessionId(), SESSION_ID);
        require(value.getAid(), AID);
        require(value.getDebug(), DEBUG);
        return value;
    }

    private static void require(Object value, String key) {
        if (value == null || (value instanceof String && ((String) value).trim().isEmpty())) {
            throw new IllegalStateException("HITL_CONTEXT_ATTRIBUTE_MISSING: " + key);
        }
    }

    private static Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException error) {
            throw new IllegalStateException("invalid long context attribute: " + value, error);
        }
    }

    private static Boolean toBoolean(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean) return (Boolean) value;
        String text = String.valueOf(value);
        if ("true".equalsIgnoreCase(text)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(text)) return Boolean.FALSE;
        throw new IllegalStateException("invalid boolean context attribute: " + value);
    }

    private static String toStringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
