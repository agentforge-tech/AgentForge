package cloud.changlu.agentforge.agent.governance.tool.askuser;

import cloud.changlu.agentforge.model.tool.P;

import java.util.List;

/** 一次 ask_user 调用中的单个问题。 */
public final class AskQuestion {
    @P(name = "id", description = "Stable semantic id unique within this tool call")
    String id;

    @P(name = "title", description = "Complete question text shown to the user")
    String title;

    @P(
            name = "description",
            description = "Optional context explaining why it matters",
            required = false)
    String description;

    @P(name = "type", description = "single_choice, multiple_choice or text")
    AskUserAnswerType type;

    @P(
            name = "required",
            description = "Whether the user must answer this question",
            required = false)
    Boolean required;

    @P(
            name = "options",
            description =
                    "Two to twenty real options for choice questions; empty for text. "
                            + "Do not add placeholder options like 'Other' or 'I will fill in'; the client "
                            + "always renders a custom answer input next to the options.",
            required = false)
    List<AskUserOption> options;

    @P(
            name = "minSelections",
            description = "Minimum answer count for multiple_choice",
            required = false)
    Integer minSelections;

    @P(
            name = "maxSelections",
            description = "Maximum answer count for multiple_choice",
            required = false)
    Integer maxSelections;

    @P(
            name = "maxLength",
            description = "Maximum text length; defaults to 4000 and cannot exceed 4000",
            required = false)
    Integer maxLength;

    @P(name = "placeholder", description = "Placeholder shown in the text input", required = false)
    String placeholder;

    public AskQuestion() {}

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public AskUserAnswerType getType() {
        return type;
    }

    public Boolean getRequired() {
        return required;
    }

    public boolean isRequired() {
        return required == null || required.booleanValue();
    }

    public List<AskUserOption> getOptions() {
        return options;
    }

    public Integer getMinSelections() {
        return minSelections;
    }

    public Integer getMaxSelections() {
        return maxSelections;
    }

    public Integer getMaxLength() {
        return maxLength;
    }

    public String getPlaceholder() {
        return placeholder;
    }
}
