package cloud.changlu.agentforge.agent.governance.interrupt.domain;

import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserResponse;

/** 统一恢复项：审批填 action，AskUser 填结构化 answers。 */
public final class InterruptResponse {
    private final String interruptId;
    private final ApprovalAction action;
    private final AskUserResponse askUserResponse;

    public InterruptResponse(
            String interruptId, ApprovalAction action, AskUserResponse askUserResponse) {
        this.interruptId = interruptId;
        this.action = action;
        this.askUserResponse = askUserResponse;
    }

    /** 构造结构化 AskUser 回答；与审批 action 互斥。 */
    public static InterruptResponse askUser(String interruptId, AskUserResponse response) {
        return new InterruptResponse(interruptId, null, response);
    }

    public String getInterruptId() {
        return interruptId;
    }

    public ApprovalAction getAction() {
        return action;
    }

    public AskUserResponse getAskUserResponse() {
        return askUserResponse;
    }
}
