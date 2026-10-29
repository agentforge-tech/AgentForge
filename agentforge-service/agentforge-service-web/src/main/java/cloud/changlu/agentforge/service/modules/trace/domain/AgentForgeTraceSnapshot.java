package cloud.changlu.agentforge.service.modules.trace.domain;

import cloud.changlu.agentforge.agent.trace.domain.AgentTraceSnapshot;

/** AgentForge Service extension of the framework-neutral trace snapshot. */
public class AgentForgeTraceSnapshot extends AgentTraceSnapshot {

    private String aid;
    private Boolean debug;

    public String getAid() {
        return aid;
    }

    public void setAid(String aid) {
        this.aid = aid;
    }

    public Boolean getDebug() {
        return debug;
    }

    public void setDebug(Boolean debug) {
        this.debug = debug;
    }
}
