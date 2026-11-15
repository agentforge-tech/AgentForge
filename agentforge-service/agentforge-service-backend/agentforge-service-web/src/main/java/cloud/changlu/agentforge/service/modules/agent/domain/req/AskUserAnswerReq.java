package cloud.changlu.agentforge.service.modules.agent.domain.req;

import java.util.List;

/** AskUser 单题回答。 */
public class AskUserAnswerReq {
    private String questionId;
    private List<String> selectedOptionIds;
    private String customValue;

    public String getQuestionId() {
        return questionId;
    }

    public void setQuestionId(String questionId) {
        this.questionId = questionId;
    }

    public List<String> getSelectedOptionIds() {
        return selectedOptionIds;
    }

    public void setSelectedOptionIds(List<String> selectedOptionIds) {
        this.selectedOptionIds = selectedOptionIds;
    }

    public String getCustomValue() {
        return customValue;
    }

    public void setCustomValue(String customValue) {
        this.customValue = customValue;
    }
}
