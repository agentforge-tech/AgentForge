package cloud.changlu.agentforge.agent.governance.tool.askuser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** AskUser 的结构化批量回答。 */
public final class AskUserResponse {
    private final List<AskUserAnswer> answers;

    public AskUserResponse(List<AskUserAnswer> answers) {
        this.answers =
                answers == null
                        ? Collections.<AskUserAnswer>emptyList()
                        : Collections.unmodifiableList(new ArrayList<AskUserAnswer>(answers));
    }

    public static AskUserResponse of(AskUserAnswer... answers) {
        return new AskUserResponse(answers == null ? null : Arrays.asList(answers));
    }

    public static AskUserResponse single(String questionId, String optionId) {
        return of(AskUserAnswer.single(questionId, optionId));
    }

    public static AskUserResponse text(String questionId, String text) {
        return of(AskUserAnswer.text(questionId, text));
    }

    public List<AskUserAnswer> getAnswers() {
        return answers;
    }
}
