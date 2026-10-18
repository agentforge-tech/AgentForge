package cloud.changlu.agentforge.service.modules.agent.runtime.mode.react;

import cloud.changlu.agentforge.agent.ReActAgent;

/** StandardAgentConfig 对应的标准 ReActAgent 实现。 */
public class StandardReActAgent extends ReActAgent {

    protected StandardReActAgent(ReActAgentBuilder builder) {
        super(builder);
    }

    public static StandardReActAgent from(ReActAgentBuilder builder) {
        return new StandardReActAgent(builder);
    }
}
