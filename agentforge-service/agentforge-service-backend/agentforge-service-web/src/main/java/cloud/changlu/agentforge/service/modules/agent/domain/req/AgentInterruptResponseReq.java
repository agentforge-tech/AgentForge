package cloud.changlu.agentforge.service.modules.agent.domain.req;

import java.util.List;

/** 一个中断项的审批或 AskUser 回答。 */
public class AgentInterruptResponseReq {
    private String interruptId;
    private String action;
    private List<AskUserAnswerReq> answers;

    public String getInterruptId() {
        return interruptId;
    }

    public void setInterruptId(String interruptId) {
        this.interruptId = interruptId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public List<AskUserAnswerReq> getAnswers() {
        return answers;
    }

    public void setAnswers(List<AskUserAnswerReq> answers) {
        this.answers = answers;
    }
}
