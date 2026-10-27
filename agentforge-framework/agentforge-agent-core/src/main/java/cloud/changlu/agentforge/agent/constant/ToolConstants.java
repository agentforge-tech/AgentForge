package cloud.changlu.agentforge.agent.constant;

/** Constants shared by tool schema construction. */
public final class ToolConstants {

    /** Synthetic argument used by a model to explain why it is invoking a tool. */
    public static final String THOUGHT_PARAMETER_NAME = "__thought";

    /** Prompt attached to {@link #THOUGHT_PARAMETER_NAME} in the tool JSON schema. */
    public static final String THOUGHT_PARAMETER_DESCRIPTION =
            "Explain why this tool is needed for the current task, what information it should "
                    + "obtain, and how the result supports the next step.";

    private ToolConstants() {}
}
