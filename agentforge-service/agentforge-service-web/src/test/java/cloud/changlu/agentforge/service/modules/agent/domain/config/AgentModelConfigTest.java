package cloud.changlu.agentforge.service.modules.agent.domain.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentModelConfigTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void shouldReadLegacyTokenFieldNames() {
        StandardAgentConfig config =
                StandardAgentConfig.fromJson(
                        "{\"chatModel\":{\"llm_model_id\":1,\"maxInputToken\":128,\"maxOutputToken\":64}}",
                        objectMapper);

        assertEquals(Integer.valueOf(128), config.getChatModel().getMaxInputKTokens());
        assertEquals(Integer.valueOf(64), config.getChatModel().getMaxOutputKTokens());
    }

    @Test
    public void shouldWriteUnitExplicitTokenFieldNames() {
        AgentModelConfig model = new AgentModelConfig();
        model.setMaxInputKTokens(128);
        model.setMaxOutputKTokens(64);
        StandardAgentConfig config = new StandardAgentConfig();
        config.setChatModel(model);

        String json = StandardAgentConfig.toJson(config, objectMapper);

        assertTrue(json.contains("\"maxInputKTokens\":128"));
        assertTrue(json.contains("\"maxOutputKTokens\":64"));
        assertFalse(json.contains("\"maxInputToken\""));
        assertFalse(json.contains("\"maxOutputToken\""));
    }
}
