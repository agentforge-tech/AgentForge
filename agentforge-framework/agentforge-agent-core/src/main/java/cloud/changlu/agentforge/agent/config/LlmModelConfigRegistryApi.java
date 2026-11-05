package cloud.changlu.agentforge.agent.config;

import java.util.List;

/** LLM 模型配置注册表的完整能力接口。 */
public interface LlmModelConfigRegistryApi {
    LlmModelConfig register(LlmModelConfig config);

    LlmModelConfig getConfig(String baseUrl, String modelName);

    boolean hasExactConfig(String baseUrl, String modelName);

    boolean hasModelNameConfig(String modelName);

    List<LlmModelConfig> registeredConfigs();

    default int getMaxInputTokens(String baseUrl, String modelName) {
        return getConfig(baseUrl, modelName).getMaxInputTokens();
    }

    default int getMaxOutputTokens(String baseUrl, String modelName) {
        return getConfig(baseUrl, modelName).getMaxOutputTokens();
    }
}
