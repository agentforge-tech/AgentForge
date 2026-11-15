package cloud.changlu.agentforge.agent.governance.permission.engine;

import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionMode;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;

/** 权限评估的不可变上下文。 */
public final class PermissionContext {
    private final Object memoryId;
    private final String executionId;
    private final ToolExecutionRequest request;
    private final PermissionMode mode;

    public PermissionContext(
            Object memoryId,
            String executionId,
            ToolExecutionRequest request,
            PermissionMode mode) {
        this.memoryId = memoryId;
        this.executionId = executionId;
        this.request = request;
        this.mode = mode;
    }

    public Object getMemoryId() {
        return memoryId;
    }

    public String getExecutionId() {
        return executionId;
    }

    public ToolExecutionRequest getRequest() {
        return request;
    }

    public PermissionMode getMode() {
        return mode;
    }
}
