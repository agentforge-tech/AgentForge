package cloud.changlu.agentforge.agent.governance.tool.askuser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 从模型发起的 ask_user Tool Call 中解析出的一组结构化问题。 */
public final class AskUserRequest {
    private final String toolCallId;
    private final String toolName;
    private final List<AskQuestion> questions;
    private final String reason;
    private final String submitLabel;

    public AskUserRequest(
            String toolCallId,
            String toolName,
            List<AskQuestion> questions,
            String reason,
            String submitLabel) {
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.questions =
                questions == null
                        ? Collections.<AskQuestion>emptyList()
                        : Collections.unmodifiableList(new ArrayList<AskQuestion>(questions));
        this.reason = reason;
        this.submitLabel = submitLabel;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public String getToolName() {
        return toolName;
    }

    public List<AskQuestion> getQuestions() {
        return questions;
    }

    public String getReason() {
        return reason;
    }

    public String getSubmitLabel() {
        return submitLabel;
    }
}
