package cloud.changlu.agentforge.service.modules.agent.domain.config;

/** Agent 能力集合配置。 */
public class AgentCapabilityConfig {

    /** 是否开启任务计划（todoplan）能力，默认开启。 */
    private Boolean planEnabled = true;

    /** 是否开启 AskUser/HITL 能力，默认关闭。 */
    private Boolean askUserEnabled = false;

    public Boolean getPlanEnabled() {
        return planEnabled;
    }

    public void setPlanEnabled(Boolean planEnabled) {
        this.planEnabled = planEnabled;
    }

    public Boolean getAskUserEnabled() {
        return askUserEnabled;
    }

    public void setAskUserEnabled(Boolean askUserEnabled) {
        this.askUserEnabled = askUserEnabled;
    }
}
