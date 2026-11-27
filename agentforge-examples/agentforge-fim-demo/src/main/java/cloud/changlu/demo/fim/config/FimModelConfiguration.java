package cloud.changlu.demo.fim.config;

import cloud.changlu.agentforge.model.fim.FimModel;
import cloud.changlu.agentforge.model.fim.StreamingFimModel;
import cloud.changlu.agentforge.model.registry.LlmFactory;
import cloud.changlu.agentforge.model.registry.config.LlmBasicConfig;
import cloud.changlu.agentforge.model.registry.constant.LlmConstant;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 通过 AgentForge 的 {@link LlmFactory} 装配 FIM 模型。
 *
 * <p>刻意<b>不</b>直接 new {@code OpenAiFimModel}：演示工程应该展示"框架标准用法"—— 只依赖 provider 枚举 + url + 配置项，不耦合任何
 * Provider 实现类。 这样换成 DeepSeek / DashScope / vLLM 只需改配置。
 *
 * @author changlu
 * @date 2026/11/26
 */
@Configuration
public class FimModelConfiguration {

    /**
     * 同步 FIM 模型。
     *
     * <p>用 {@code provider=OPENAI} 表示"OpenAI Completions 兼容服务"， Xinference / vLLM / Ollama / TGI
     * 都走这一条。
     */
    @Bean
    public FimModel fimModel(FimProperties properties) {
        return LlmFactory.buildFimModel(buildConfig(properties));
    }

    /** 流式 FIM 模型，与同步版本共用同一份配置。 */
    @Bean
    public StreamingFimModel streamingFimModel(FimProperties properties) {
        return LlmFactory.buildStreamingFimModel(buildConfig(properties));
    }

    private LlmBasicConfig buildConfig(FimProperties properties) {
        LlmBasicConfig.Builder builder =
                LlmBasicConfig.builder()
                        .provider(properties.getProviderCode())
                        .url(properties.getBaseUrl())
                        .modelName(properties.getModelName())
                        .apiKey(properties.getApiKey())
                        .prop(LlmConstant.FIM_TEMPLATE, properties.getTemplate())
                        .prop(LlmConstant.TEMPERATURE, String.valueOf(properties.getTemperature()))
                        .prop(LlmConstant.MAX_TOKENS, String.valueOf(properties.getMaxTokens()))
                        .prop(LlmConstant.TIMEOUT, String.valueOf(properties.getTimeoutSeconds()));

        // stop 是补全质量的关键：不配会一次吐出整个文件
        if (properties.getStop() != null && !properties.getStop().trim().isEmpty()) {
            builder.prop(LlmConstant.STOP, properties.getStop());
        }
        return builder.build();
    }
}
