package cloud.changlu.agentforge.agent.governance.permission.spi;

import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.ApprovalAction;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;

/** 用户审批授权的查询与记录扩展点。 */
public interface PermissionApprovalStore {

    /** 判断当前上下文中的工具调用是否已经获得后续执行授权。 */
    boolean isApproved(AgentChatContext context, ToolExecutionRequest request);

    /**
     * 处理一次用户审批结果。
     *
     * <p>实现可以观察全部三种 action，但只有 APPROVE_ALWAYS 应形成后续调用可命中的授权。
     */
    void addApproval(AgentChatContext context, ToolExecutionRequest request, ApprovalAction action);
}
