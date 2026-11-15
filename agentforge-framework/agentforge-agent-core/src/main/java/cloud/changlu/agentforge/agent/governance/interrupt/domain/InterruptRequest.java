package cloud.changlu.agentforge.agent.governance.interrupt.domain;

import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserRequest;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;

/**
 * 批次中单个等待外部响应的 Tool Call 中断项。
 *
 * <p>每个中断项通过 {@link #getInterruptId()} 与恢复响应一一对应。
 *
 * <p>{@link #getToolRequest()} 绑定原始 Tool Call，{@link #getType()} 区分权限审核与 AskUser。
 *
 * <p>权限审核的 {@link #getPayload()} 为空；AskUser 的 payload 为 {@link AskUserRequest}。
 *
 * <p>恢复时不得替换工具名或参数，也不得为同一 interruptId 提交多个响应。
 */
public final class InterruptRequest {
    private final String interruptId;
    private final InterruptType type;
    private final ToolExecutionRequest toolRequest;
    private final Object payload;

    public InterruptRequest(
            String interruptId, InterruptType type, ToolExecutionRequest toolRequest) {
        this(interruptId, type, toolRequest, null);
    }

    public InterruptRequest(
            String interruptId,
            InterruptType type,
            ToolExecutionRequest toolRequest,
            Object payload) {
        this.interruptId = interruptId;
        this.type = type;
        this.toolRequest = toolRequest;
        this.payload = payload;
    }

    public String getInterruptId() {
        return interruptId;
    }

    public InterruptType getType() {
        return type;
    }

    public ToolExecutionRequest getToolRequest() {
        return toolRequest;
    }

    /** 类型专属请求内容；ASK_USER 时是 {@link AskUserRequest}。 */
    public Object getPayload() {
        return payload;
    }

    public AskUserRequest getAskUserRequest() {
        return payload instanceof AskUserRequest ? (AskUserRequest) payload : null;
    }
}
