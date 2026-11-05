package cloud.changlu.agentforge.agent.config;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class LlmModelConfigRegistryTest {
    @Test
    public void shouldExposeRegistrationAndResolutionThroughOneInterface() {
        LlmModelConfigRegistryApi registry = LlmModelConfigRegistry.registry();
        LlmModelConfig registered =
                registry.register(
                        LlmModelConfig.builder()
                                .baseUrl("https://interfaces.example/v1")
                                .modelName("interface-model")
                                .maxInputKTokens(128)
                                .maxOutputKTokens(16)
                                .supportsText(true)
                                .supportsImage(false)
                                .build());

        assertSame(
                registered, registry.getConfig("https://interfaces.example/v1", "interface-model"));
    }

    @Test
    public void shouldMatchBuiltInConfig() {
        LlmModelConfig config =
                LlmModelConfigRegistry.getConfig("https://api.openai.com/v1/", "gpt-5.4-mini");
        assertEquals(400, config.getMaxInputKTokens());
        assertEquals(128000, config.getMaxOutputTokens());
        assertTrue(config.isSupportsText());
        assertTrue(config.isSupportsImage());
    }

    @Test
    public void shouldRegisterStandaloneConfig() {
        LlmModelConfig registered =
                LlmModelConfigRegistry.register(
                        LlmModelConfig.builder()
                                .baseUrl("https://models.example/v1/")
                                .modelName("vision-model")
                                .maxInputKTokens(256)
                                .maxOutputKTokens(32)
                                .supportsText(true)
                                .supportsImage(true)
                                .build());
        LlmModelConfig result =
                LlmModelConfigRegistry.getConfig("https://models.example/v1", "vision-model");
        assertSame(registered, result);
        assertTrue(result.isSupportsImage());
    }

    @Test
    public void shouldFallBackToModelNameAndDefault() {
        LlmModelConfig byName =
                LlmModelConfigRegistry.getConfig("https://custom.example/v1", "mimo-v2-flash");
        assertEquals(256, byName.getMaxInputKTokens());
        assertFalse(byName.isSupportsImage());
        LlmModelConfig fallback = LlmModelConfigRegistry.getConfig(null, "unknown-model");
        assertEquals(128000, fallback.getMaxInputTokens());
        assertEquals(64000, fallback.getMaxOutputTokens());
    }

    @Test
    public void shouldRegisterLatestDeepSeekFlashAliasesAsMultimodal() {
        LlmModelConfig official =
                LlmModelConfigRegistry.getConfig("https://api.deepseek.com", "deepseek-flash");
        LlmModelConfig versionAlias =
                LlmModelConfigRegistry.getConfig("https://api.deepseek.com", "deepseek-v4.1-flash");

        assertEquals(1024, official.getMaxInputKTokens());
        assertEquals(384, official.getMaxOutputKTokens());
        assertTrue(official.isSupportsImage());
        assertTrue(versionAlias.isSupportsImage());
    }
}
