package cloud.changlu.agentforge.agent.governance.tool.askuser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 用户对单个 AskQuestion 的回答。 */
public final class AskUserAnswer {
    private final String questionId;
    private final List<String> selectedOptionIds;
    private final String customValue;

    public AskUserAnswer(String questionId, List<String> selectedOptionIds, String customValue) {
        this.questionId = questionId;
        this.selectedOptionIds =
                selectedOptionIds == null
                        ? Collections.<String>emptyList()
                        : Collections.unmodifiableList(new ArrayList<String>(selectedOptionIds));
        this.customValue = customValue == null ? null : customValue.trim();
    }

    public static AskUserAnswer single(String questionId, String optionId) {
        return new AskUserAnswer(questionId, Collections.singletonList(optionId), null);
    }

    public static AskUserAnswer multiple(String questionId, List<String> optionIds) {
        return new AskUserAnswer(questionId, optionIds, null);
    }

    public static AskUserAnswer text(String questionId, String text) {
        return new AskUserAnswer(questionId, null, text);
    }

    public String getQuestionId() {
        return questionId;
    }

    public List<String> getSelectedOptionIds() {
        return selectedOptionIds;
    }

    public String getCustomValue() {
        return customValue;
    }
}
