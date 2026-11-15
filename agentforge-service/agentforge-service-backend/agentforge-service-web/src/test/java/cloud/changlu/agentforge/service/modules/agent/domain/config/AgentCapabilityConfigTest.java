package cloud.changlu.agentforge.service.modules.agent.domain.config;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentCapabilityConfigTest {

    @Test
    public void shouldEnablePlanByDefault() {
        assertTrue(new StandardAgentConfig().isPlanEnabled());
    }

    @Test
    public void shouldEnablePlanWhenValueIsNull() {
        StandardAgentConfig config = new StandardAgentConfig();
        AgentCapabilityConfig capabilityConfig = new AgentCapabilityConfig();
        capabilityConfig.setPlanEnabled(null);
        config.setCapabilityConfig(capabilityConfig);

        assertTrue(config.isPlanEnabled());
    }

    @Test
    public void shouldDisablePlanWhenExplicitlyFalse() {
        StandardAgentConfig config = new StandardAgentConfig();
        AgentCapabilityConfig capabilityConfig = new AgentCapabilityConfig();
        capabilityConfig.setPlanEnabled(false);
        config.setCapabilityConfig(capabilityConfig);

        assertFalse(config.isPlanEnabled());
    }

    @Test
    public void shouldDisableAskUserByDefault() {
        assertFalse(new StandardAgentConfig().isAskUserEnabled());
    }

    @Test
    public void shouldEnableAskUserOnlyWhenExplicitlyTrue() {
        StandardAgentConfig config = new StandardAgentConfig();
        AgentCapabilityConfig capabilityConfig = new AgentCapabilityConfig();
        capabilityConfig.setAskUserEnabled(true);
        config.setCapabilityConfig(capabilityConfig);

        assertTrue(config.isAskUserEnabled());
    }
}
