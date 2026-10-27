package cloud.changlu.agentforge.service.modules.agent.service;

import cloud.changlu.agentforge.service.modules.agent.dao.AgentConfigMapper;
import cloud.changlu.agentforge.service.modules.agent.dao.AgentConfigPublishedMapper;
import cloud.changlu.agentforge.service.modules.agent.domain.AgentConfigPojo;
import cloud.changlu.agentforge.service.modules.agent.domain.AgentConfigPublishedPojo;
import cloud.changlu.agentforge.service.modules.agent.domain.AgentRuntimeConfig;
import cloud.changlu.agentforge.service.modules.agent.exception.AgentUnavailableException;

import org.junit.Test;

import java.lang.reflect.Proxy;

import static org.junit.Assert.assertEquals;

public class AgentRuntimeConfigResolverTest {

    @Test
    public void debugUsesCurrentConfig() {
        AgentConfigPojo current = new AgentConfigPojo();
        current.setAid("agent-1");
        current.setType(1);
        current.setAgentConfig("current-config");
        AgentConfigPublishedPojo published = published("published-config");
        AgentRuntimeConfigResolver resolver = resolver(current, published);

        AgentRuntimeConfig result = resolver.resolve(" agent-1 ", true);

        assertEquals("agent-1", result.getAid());
        assertEquals("current-config", result.getAgentConfig());
    }

    @Test
    public void formalUsesPublishedSnapshot() {
        AgentConfigPojo current = new AgentConfigPojo();
        current.setAid("agent-1");
        current.setType(1);
        current.setAgentConfig("unpublished-edit");
        AgentRuntimeConfigResolver resolver = resolver(current, published("published-config"));

        AgentRuntimeConfig result = resolver.resolve("agent-1", false);

        assertEquals("published-config", result.getAgentConfig());
    }

    @Test(expected = AgentUnavailableException.class)
    public void formalDoesNotFallbackToCurrentConfig() {
        AgentConfigPojo current = new AgentConfigPojo();
        current.setAid("agent-1");
        current.setType(1);
        current.setAgentConfig("current-config");

        resolver(current, null).resolve("agent-1", false);
    }

    private AgentRuntimeConfigResolver resolver(
            AgentConfigPojo current, AgentConfigPublishedPojo published) {
        AgentConfigMapper currentMapper =
                (AgentConfigMapper)
                        Proxy.newProxyInstance(
                                AgentConfigMapper.class.getClassLoader(),
                                new Class<?>[] {AgentConfigMapper.class},
                                (proxy, method, args) ->
                                        "selectByAid".equals(method.getName()) ? current : null);
        AgentConfigPublishedMapper publishedMapper =
                (AgentConfigPublishedMapper)
                        Proxy.newProxyInstance(
                                AgentConfigPublishedMapper.class.getClassLoader(),
                                new Class<?>[] {AgentConfigPublishedMapper.class},
                                (proxy, method, args) ->
                                        "selectByAid".equals(method.getName()) ? published : null);
        return new AgentRuntimeConfigResolver(currentMapper, publishedMapper);
    }

    private AgentConfigPublishedPojo published(String agentConfig) {
        AgentConfigPublishedPojo published = new AgentConfigPublishedPojo();
        published.setAid("agent-1");
        published.setType(1);
        published.setAgentConfig(agentConfig);
        return published;
    }
}
