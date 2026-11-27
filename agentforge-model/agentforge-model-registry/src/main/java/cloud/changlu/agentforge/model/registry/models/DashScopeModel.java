package cloud.changlu.agentforge.model.registry.models;

import cloud.changlu.agentforge.model.chat.ChatModel;
import cloud.changlu.agentforge.model.chat.StreamingChatModel;
import cloud.changlu.agentforge.model.dashscope.fim.DashScopeFimModel;
import cloud.changlu.agentforge.model.dashscope.fim.DashScopeStreamingFimModel;
import cloud.changlu.agentforge.model.fim.FimModel;
import cloud.changlu.agentforge.model.fim.StreamingFimModel;
import cloud.changlu.agentforge.model.registry.config.LlmBasicConfig;
import cloud.changlu.agentforge.model.registry.constant.LlmConstant;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * @description 阿里云百炼（DashScope）构建：FIM 走 Partial Mode，Chat 复用 OpenAI 兼容协议
 * @author changlu
 * @date 2026/11/25
 */
public class DashScopeModel extends OpenAiModel {

    /** 百炼 OpenAI 兼容模式默认 Base（旧域名，官方仍可用）。 */
    public static final String DEFAULT_BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /** Partial Mode 支持列表内的默认模型。 */
    public static final String DEFAULT_MODEL = "qwen3-coder-flash";

    @Override
    public ChatModel buildChatModel(LlmBasicConfig llmBasicConfig) {
        // Chat 侧走百炼的 OpenAI 兼容 /chat/completions，可直接复用父类实现
        return super.buildChatModel(normalize(llmBasicConfig));
    }

    @Override
    public StreamingChatModel buildStreamChatModel(LlmBasicConfig llmBasicConfig) {
        return super.buildStreamChatModel(normalize(llmBasicConfig));
    }

    @Override
    public FimModel buildFimModel(LlmBasicConfig llmBasicConfig) {
        DashScopeFimModel.Builder builder =
                DashScopeFimModel.builder()
                        .baseUrl(normalize(llmBasicConfig).getUrl())
                        .modelName(resolveModelName(llmBasicConfig.getModelName()))
                        .apiKey(llmBasicConfig.getApiKey());
        applyFimProps(builder, llmBasicConfig.getProps());
        return builder.build();
    }

    @Override
    public StreamingFimModel buildStreamingFimModel(LlmBasicConfig llmBasicConfig) {
        DashScopeStreamingFimModel.Builder builder =
                DashScopeStreamingFimModel.builder()
                        .baseUrl(normalize(llmBasicConfig).getUrl())
                        .modelName(resolveModelName(llmBasicConfig.getModelName()))
                        .apiKey(llmBasicConfig.getApiKey());
        applyFimProps(builder, llmBasicConfig.getProps());
        return builder.build();
    }

    private void applyFimProps(DashScopeFimModel.Builder builder, Properties props) {
        if (props == null) {
            return;
        }
        String timeout = props.getProperty(LlmConstant.TIMEOUT);
        if (isNotEmpty(timeout)) {
            int timeoutMillis = (int) (Long.parseLong(timeout.trim()) * 1000L);
            builder.connectTimeoutMillis(timeoutMillis);
            builder.readTimeoutMillis(timeoutMillis);
        }
        String temperature = props.getProperty(LlmConstant.TEMPERATURE);
        if (isNotEmpty(temperature)) {
            builder.temperature(Double.parseDouble(temperature));
        }
        String topP = props.getProperty(LlmConstant.TOP_P);
        if (isNotEmpty(topP)) {
            builder.topP(Double.parseDouble(topP));
        }
        String maxTokens = props.getProperty(LlmConstant.MAX_TOKENS);
        if (isNotEmpty(maxTokens)) {
            builder.maxTokens(Integer.parseInt(maxTokens));
        }
        String stop = props.getProperty(LlmConstant.STOP);
        if (isNotEmpty(stop)) {
            builder.stopSequences(splitStops(stop));
        }
        String userAgent = props.getProperty(LlmConstant.USER_AGENT);
        if (isNotEmpty(userAgent)) {
            builder.customHeader("User-Agent", userAgent.trim());
        }
        // Partial Mode 只有前缀续写，没有原生 suffix；这里控制是否用提示词弱约束补足
        String suffixHint = props.getProperty(LlmConstant.FIM_SUFFIX_HINT);
        if (isNotEmpty(suffixHint)) {
            builder.suffixHintEnabled(Boolean.parseBoolean(suffixHint.trim()));
        }
    }

    private void applyFimProps(DashScopeStreamingFimModel.Builder builder, Properties props) {
        if (props == null) {
            return;
        }
        String timeout = props.getProperty(LlmConstant.TIMEOUT);
        if (isNotEmpty(timeout)) {
            int timeoutMillis = (int) (Long.parseLong(timeout.trim()) * 1000L);
            builder.connectTimeoutMillis(timeoutMillis);
            builder.readTimeoutMillis(timeoutMillis);
        }
        String temperature = props.getProperty(LlmConstant.TEMPERATURE);
        if (isNotEmpty(temperature)) {
            builder.temperature(Double.parseDouble(temperature));
        }
        String topP = props.getProperty(LlmConstant.TOP_P);
        if (isNotEmpty(topP)) {
            builder.topP(Double.parseDouble(topP));
        }
        String maxTokens = props.getProperty(LlmConstant.MAX_TOKENS);
        if (isNotEmpty(maxTokens)) {
            builder.maxTokens(Integer.parseInt(maxTokens));
        }
        String stop = props.getProperty(LlmConstant.STOP);
        if (isNotEmpty(stop)) {
            builder.stopSequences(splitStops(stop));
        }
        String userAgent = props.getProperty(LlmConstant.USER_AGENT);
        if (isNotEmpty(userAgent)) {
            builder.customHeader("User-Agent", userAgent.trim());
        }
        String suffixHint = props.getProperty(LlmConstant.FIM_SUFFIX_HINT);
        if (isNotEmpty(suffixHint)) {
            builder.suffixHintEnabled(Boolean.parseBoolean(suffixHint.trim()));
        }
    }

    /** 缺省 URL / 模型名时回落到百炼官方默认值，避免用户漏配直接 404。 */
    private static LlmBasicConfig normalize(LlmBasicConfig source) {
        String url = source.getUrl();
        String model = source.getModelName();
        LlmBasicConfig.Builder builder =
                LlmBasicConfig.builder()
                        .provider(source.getProvider())
                        .url(url == null || url.trim().isEmpty() ? DEFAULT_BASE_URL : url.trim())
                        .modelName(model == null || model.trim().isEmpty() ? DEFAULT_MODEL : model)
                        .apiKey(source.getApiKey())
                        .props(source.getProps());
        return builder.build();
    }

    private static String resolveModelName(String modelName) {
        return modelName == null || modelName.trim().isEmpty() ? DEFAULT_MODEL : modelName.trim();
    }

    private static List<String> splitStops(String raw) {
        List<String> result = new ArrayList<String>();
        for (String part : raw.split("\\|")) {
            if (!part.isEmpty()) {
                result.add(part);
            }
        }
        return result;
    }
}
