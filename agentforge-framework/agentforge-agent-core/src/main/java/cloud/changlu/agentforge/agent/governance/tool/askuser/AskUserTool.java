package cloud.changlu.agentforge.agent.governance.tool.askuser;

import cloud.changlu.agentforge.model.tool.P;
import cloud.changlu.agentforge.model.tool.Tool;

import java.util.List;

/** 模型可见的询问工具；框架在执行前将其转换为 ASK_USER 中断。 */
public final class AskUserTool {
    public static final String TOOL_NAME = "ask_user";

    @Tool(
            name = TOOL_NAME,
            value =
                    "Ask the user for missing decisions before continuing. Group related questions into one call. "
                            + "Supported types are single_choice, multiple_choice and text. Use stable readable ids. "
                            + "Only list real candidate options; the client already provides a custom answer input, "
                            + "so never add placeholder options such as 'Other' or 'I will fill in'. "
                            + "Call this tool alone; the runtime pauses until the user answers.")
    public String ask(
            @P(name = "questions", description = "One to ten questions to present to the user")
                    List<AskQuestion> questions,
            @P(name = "reason", description = "Why these decisions are needed", required = false)
                    String reason,
            @P(
                            name = "submitLabel",
                            description = "Optional confirmation button label",
                            required = false)
                    String submitLabel) {
        throw new IllegalStateException(
                "ask_user must be intercepted by PermissionInterruptModule");
    }
}
