package cloud.changlu.agentforge.agent.governance.permission.support;

import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.ApprovalAction;
import cloud.changlu.agentforge.agent.governance.permission.spi.PermissionApprovalStore;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 单进程、按 memoryId 隔离的 APPROVE_ALWAYS 默认存储。 */
public final class InMemorySessionPermissionApprovalStore implements PermissionApprovalStore {

    private final ConcurrentMap<Object, Set<String>> approvedCallsBySession =
            new ConcurrentHashMap<Object, Set<String>>();

    @Override
    public boolean isApproved(AgentChatContext context, ToolExecutionRequest request) {
        Object memoryId = requiredMemoryId(context);
        Set<String> approvedCalls = approvedCallsBySession.get(memoryId);
        return approvedCalls != null && approvedCalls.contains(fingerprint(request));
    }

    @Override
    public void addApproval(
            AgentChatContext context, ToolExecutionRequest request, ApprovalAction action) {
        if (action == null) {
            throw new IllegalArgumentException("approval action must not be null");
        }
        if (action != ApprovalAction.APPROVE_ALWAYS) {
            return;
        }
        Object memoryId = requiredMemoryId(context);
        Set<String> newSet = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
        Set<String> approvedCalls = approvedCallsBySession.putIfAbsent(memoryId, newSet);
        if (approvedCalls == null) {
            approvedCalls = newSet;
        }
        approvedCalls.add(fingerprint(request));
    }

    private static Object requiredMemoryId(AgentChatContext context) {
        if (context == null || context.getMemoryId() == null) {
            throw new IllegalArgumentException(
                    "chatContext.memoryId must not be null for permission approval");
        }
        return context.getMemoryId();
    }

    private static String fingerprint(ToolExecutionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("tool request must not be null");
        }
        return request.name() + "\n" + request.arguments();
    }
}
