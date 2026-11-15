package cloud.changlu.agentforge.agent.governance.permission.domain;

import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;

/**
 * 权限引擎直接拒绝的一次 Tool Call。
 *
 * <p>DENY 不创建用户中断，也不执行工具；恢复或本轮执行时会把该原因写成失败 Tool Result， 保证 Function Call 消息链闭合，并让模型根据拒绝结果继续下一轮推理。
 */
public final class PermissionDenial {
    private final ToolExecutionRequest request;
    private final String reason;

    public PermissionDenial(ToolExecutionRequest request, String reason) {
        this.request = request;
        this.reason = reason;
    }

    public ToolExecutionRequest getRequest() {
        return request;
    }

    public String getReason() {
        return reason;
    }
}
