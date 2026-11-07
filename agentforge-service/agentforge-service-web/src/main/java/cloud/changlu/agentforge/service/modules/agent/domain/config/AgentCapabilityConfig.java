package cloud.changlu.agentforge.service.modules.agent.domain.config;

/** Agent 能力集合配置。 */
public class AgentCapabilityConfig {

    /** 是否开启任务计划（todoplan）能力，默认开启。 */
    private Boolean planEnabled = true;

    public Boolean getPlanEnabled() {
        return planEnabled;
    }

    public void setPlanEnabled(Boolean planEnabled) {
        this.planEnabled = planEnabled;
    }
}
