package cloud.changlu.agentforge.model.registry.models;

import cloud.changlu.agentforge.model.chat.ChatModel;
import cloud.changlu.agentforge.model.chat.StreamingChatModel;
import cloud.changlu.agentforge.model.fim.FimModel;
import cloud.changlu.agentforge.model.fim.StreamingFimModel;
import cloud.changlu.agentforge.model.openai.OpenAiChatModel;
import cloud.changlu.agentforge.model.openai.OpenAiStreamingChatModel;
import cloud.changlu.agentforge.model.openai.fim.OpenAiFimModel;
import cloud.changlu.agentforge.model.openai.fim.OpenAiStreamingFimModel;
import cloud.changlu.agentforge.model.registry.config.LlmBasicConfig;
import cloud.changlu.agentforge.model.registry.constant.LlmConstant;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * @description DeepSeek 模型构建：Chat 走 {@code /chat/completions}，FIM 走 Beta 的 {@code /completions}
 * @author changlu
 * @date 2026/11/25
 */
public class DeepSeekModel extends OpenAiModel {

    /** 官方基础地址（不带 /beta）。 */
    public static final String OFFICIAL_BASE_URL = "https://api.deepseek.com";

    private static final String BETA_SUFFIX = "/beta";

    @Override
    public ChatModel buildChatModel(LlmBasicConfig llmBasicConfig) {
        return OpenAiChatModel.builder()
                .baseUrl(normalizeChatBase(llmBasicConfig.getUrl()))
                .modelName(llmBasicConfig.getModelName())
                .apiKey(llmBasicConfig.getApiKey())
                .build();
    }

    @Override
    public StreamingChatModel buildStreamChatModel(LlmBasicConfig llmBasicConfig) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(normalizeChatBase(llmBasicConfig.getUrl()))
                .modelName(llmBasicConfig.getModelName())
                .apiKey(llmBasicConfig.getApiKey())
                .build();
    }

    @Override
    public FimModel buildFimModel(LlmBasicConfig llmBasicConfig) {
        OpenAiFimModel.Builder builder =
                OpenAiFimModel.builder()
                        .baseUrl(normalizeBetaBase(llmBasicConfig.getUrl()))
                        .modelName(resolveModelName(llmBasicConfig.getModelName()))
                        .apiKey(llmBasicConfig.getApiKey());
        applyFimProps(builder, llmBasicConfig.getProps());
        return builder.build();
    }

    @Override
    public StreamingFimModel buildStreamingFimModel(LlmBasicConfig llmBasicConfig) {
        OpenAiStreamingFimModel.Builder builder =
                OpenAiStreamingFimModel.builder()
                        .baseUrl(normalizeBetaBase(llmBasicConfig.getUrl()))
                        .modelName(resolveModelName(llmBasicConfig.getModelName()))
                        .apiKey(llmBasicConfig.getApiKey());
        applyFimProps(builder, llmBasicConfig.getProps());
        return builder.build();
    }

    /** FIM 参数解析：复用 LlmConstant 中既有的通用键，避免另造一套配置语义。 */
    private void applyFimProps(OpenAiFimModel.Builder builder, Properties props) {
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
        String disableThinking = props.getProperty(LlmConstant.FIM_DISABLE_THINKING);
        if (isNotEmpty(disableThinking)) {
            builder.disableThinking(Boolean.parseBoolean(disableThinking.trim()));
        }
        String userAgent = props.getProperty(LlmConstant.USER_AGENT);
        if (isNotEmpty(userAgent)) {
            builder.customHeader("User-Agent", userAgent.trim());
        }
    }

    /** 流式版本参数解析。 */
    private void applyFimProps(OpenAiStreamingFimModel.Builder builder, Properties props) {
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
        String disableThinking = props.getProperty(LlmConstant.FIM_DISABLE_THINKING);
        if (isNotEmpty(disableThinking)) {
            builder.disableThinking(Boolean.parseBoolean(disableThinking.trim()));
        }
        String userAgent = props.getProperty(LlmConstant.USER_AGENT);
        if (isNotEmpty(userAgent)) {
            builder.customHeader("User-Agent", userAgent.trim());
        }
    }

    /** stop 配置按 {@code |} 分隔，便于在 properties 中表达多值。 */
    private static List<String> splitStops(String raw) {
        List<String> result = new ArrayList<String>();
        for (String part : raw.split("\\|")) {
            if (!part.isEmpty()) {
                result.add(part);
            }
        }
        return result;
    }

    private static String resolveModelName(String modelName) {
        return modelName == null || modelName.trim().isEmpty()
                ? OpenAiFimModel.DEEPSEEK_DEFAULT_MODEL
                : modelName.trim();
    }

    /** Chat 端点不加 /beta：Beta 只开放 completions 等额外能力。 */
    private static String normalizeChatBase(String url) {
        String base = url == null || url.trim().isEmpty() ? OFFICIAL_BASE_URL : url.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith(BETA_SUFFIX)) {
            base = base.substring(0, base.length() - BETA_SUFFIX.length());
        }
        return base;
    }

    /** FIM 端点必须带 /beta，否则 404。 */
    private static String normalizeBetaBase(String url) {
        String base = normalizeChatBase(url);
        return base + BETA_SUFFIX;
    }
}
