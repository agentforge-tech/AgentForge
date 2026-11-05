package cloud.changlu.agentforge.agent.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 支持运行时注册的 LLM 模型能力注册表。 */
public final class LlmModelConfigRegistry {
    private static final Map<String, LlmModelConfig> EXACT_CONFIGS = new ConcurrentHashMap<>();
    private static final Map<String, LlmModelConfig> MODEL_CONFIGS = new ConcurrentHashMap<>();
    private static final LlmModelConfig DEFAULT_CONFIG = text(null, "default", 128, 64);
    private static final LlmModelConfigRegistryApi REGISTRY_API =
            new LlmModelConfigRegistryApi() {
                @Override
                public LlmModelConfig register(LlmModelConfig config) {
                    return LlmModelConfigRegistry.register(config);
                }

                @Override
                public LlmModelConfig getConfig(String baseUrl, String modelName) {
                    return LlmModelConfigRegistry.getConfig(baseUrl, modelName);
                }

                @Override
                public boolean hasExactConfig(String baseUrl, String modelName) {
                    return LlmModelConfigRegistry.hasExactConfig(baseUrl, modelName);
                }

                @Override
                public boolean hasModelNameConfig(String modelName) {
                    return LlmModelConfigRegistry.hasModelNameConfig(modelName);
                }

                @Override
                public List<LlmModelConfig> registeredConfigs() {
                    return LlmModelConfigRegistry.registeredConfigs();
                }
            };

    static {
        // DeepSeek 官方模型列表：https://api-docs.deepseek.com/api/list-models/
        // 官方规格与定价：https://api-docs.deepseek.com/quick_start/pricing/
        // V4.1 Flash 发布及兼容别名：https://api-docs.deepseek.com/updates/
        registerProvider(
                "https://api.deepseek.com",
                image("deepseek-flash", 1024, 384),
                image("deepseek-v4.1-flash", 1024, 384),
                image("deepseek-v4.1-flash-1017", 1024, 384),
                image("deepseek-v4-flash", 1024, 384),
                text("deepseek-v4-pro", 1024, 384));
        registerProvider(
                "https://tokenrhythm.studio/v1",
                text("deepseek-v4-flash", 1024, 384),
                text("deepseek-v4-pro", 1024, 384),
                text("deepseek-v4-flash-0731", 1024, 384),
                image("seed-2.1-pro", 262, 131),
                image("seed-2.1-turbo", 262, 131),
                text("glm-5", 1024, 128),
                text("glm-5.1", 200, 128),
                text("glm-5.2", 1024, 128),
                image("kimi-k2.5", 256, 64),
                image("kimi-k2.6", 256, 128),
                image("kimi-k2.7-code", 256, 128),
                text("mimo-v2.5-pro", 256, 256),
                text("minimax-m2.5", 200, 200),
                text("minimax-m2.7", 200, 192),
                text("qwen3.7-max", 1024, 131),
                image("qwen3.8-max", 1024, 131));
        registerProvider("https://integrate.api.nvidia.com/v1", text("z-ai/glm4.7", 128, 128));
        registerProvider(
                "https://open.bigmodel.cn/api/coding/paas/v4",
                image("glm-5.3-flash", 1024, 128),
                text("glm-5.3", 1024, 128));
        registerProvider(
                "https://api.longcat.chat/openai/v1",
                image("LongCat-Flash-Omni-2603", 128, 8),
                text("LongCat-Flash-Chat", 256, 128),
                text("LongCat-Flash-Thinking", 128, 256),
                text("LongCat-Flash-Thinking-2601", 128, 256),
                text("LongCat-Flash-Lite", 256, 320));
        registerProvider(
                "https://api-inference.modelscope.cn/v1",
                text("deepseek-ai/DeepSeek-V4-Pro", 1024, 384));
        registerProvider(
                "https://token-plan-cn.xiaomimimo.com/v1",
                text("mimo-v2.5-pro", 1024, 128),
                text("mimo-v2-pro", 1024, 128),
                text("mimo-v2.5", 1024, 128),
                image("mimo-v2-omni", 256, 128),
                text("mimo-v2-flash", 256, 64));
        registerProvider(
                "https://api.openai.com/v1",
                image("gpt-5.5", 1024, 128),
                image("gpt-5.4", 1024, 128),
                image("gpt-5.4-mini", 400, 128),
                text("gpt-5.4-nano", 400, 64));
        registerProvider(
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                image("qwen3.7-plus", 1024, 64),
                image("qwen3.7-max", 1024, 64),
                image("qwen3.6-plus", 1024, 64),
                text("qwen3.6-max", 1024, 64),
                image("qwen3.6-flash", 1024, 64),
                image("qwen3.5-plus", 1024, 64),
                text("qwen3", 1024, 64));
    }

    private LlmModelConfigRegistry() {}

    /**
     * 获取默认注册表的接口视图。
     *
     * <p>业务代码可以依赖该接口完成模型配置的注册和查询。
     */
    public static LlmModelConfigRegistryApi registry() {
        return REGISTRY_API;
    }

    /** 注册模型配置；相同 baseUrl 和 modelName 的配置会被覆盖。 */
    public static LlmModelConfig register(LlmModelConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        String modelName = normalize(config.getModelName());
        String baseUrl = normalizeUrl(config.getBaseUrl());
        if (baseUrl != null) {
            EXACT_CONFIGS.put(key(baseUrl, modelName), config);
        }
        MODEL_CONFIGS.put(modelName, config);
        return config;
    }

    public static LlmModelConfig getConfig(String baseUrl, String modelName) {
        String name = normalize(modelName);
        if (name == null) {
            return DEFAULT_CONFIG;
        }
        LlmModelConfig exact = matchExact(baseUrl, name);
        LlmModelConfig byName = MODEL_CONFIGS.get(name);
        return exact != null ? exact : byName != null ? byName : DEFAULT_CONFIG;
    }

    public static boolean hasExactConfig(String baseUrl, String modelName) {
        String name = normalize(modelName);
        return name != null && matchExact(baseUrl, name) != null;
    }

    public static boolean hasModelNameConfig(String modelName) {
        String name = normalize(modelName);
        return name != null && MODEL_CONFIGS.containsKey(name);
    }

    public static List<LlmModelConfig> registeredConfigs() {
        return Collections.unmodifiableList(new ArrayList<>(EXACT_CONFIGS.values()));
    }

    public static int getMaxInputTokens(String baseUrl, String modelName) {
        return getConfig(baseUrl, modelName).getMaxInputTokens();
    }

    public static int getMaxOutputTokens(String baseUrl, String modelName) {
        return getConfig(baseUrl, modelName).getMaxOutputTokens();
    }

    private static LlmModelConfig matchExact(String baseUrl, String modelName) {
        String url = normalizeUrl(baseUrl);
        if (url == null) {
            return null;
        }
        LlmModelConfig exact = EXACT_CONFIGS.get(key(url, modelName));
        if (exact != null) {
            return exact;
        }
        for (LlmModelConfig config : EXACT_CONFIGS.values()) {
            String registeredUrl = normalizeUrl(config.getBaseUrl());
            if (modelName.equals(config.getModelName()) && urlMatches(url, registeredUrl)) {
                return config;
            }
        }
        return null;
    }

    private static void registerProvider(String baseUrl, LlmModelConfig... configs) {
        for (LlmModelConfig config : configs) {
            register(
                    LlmModelConfig.builder()
                            .baseUrl(baseUrl)
                            .modelName(config.getModelName())
                            .maxInputKTokens(config.getMaxInputKTokens())
                            .maxOutputKTokens(config.getMaxOutputKTokens())
                            .supportsText(config.isSupportsText())
                            .supportsImage(config.isSupportsImage())
                            .build());
        }
    }

    private static LlmModelConfig text(String model, int input, int output) {
        return text(null, model, input, output);
    }

    private static LlmModelConfig text(String url, String model, int input, int output) {
        return config(url, model, input, output, false);
    }

    private static LlmModelConfig image(String model, int input, int output) {
        return config(null, model, input, output, true);
    }

    private static LlmModelConfig config(
            String url, String model, int input, int output, boolean image) {
        return LlmModelConfig.builder()
                .baseUrl(url)
                .modelName(model)
                .maxInputKTokens(input)
                .maxOutputKTokens(output)
                .supportsText(true)
                .supportsImage(image)
                .build();
    }

    private static boolean urlMatches(String actual, String registered) {
        return actual.equals(registered)
                || actual.startsWith(registered + "/")
                || registered.startsWith(actual + "/");
    }

    private static String key(String url, String model) {
        return url + '\n' + model;
    }

    private static String normalizeUrl(String value) {
        String result = normalize(value);
        if (result == null) {
            return null;
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
