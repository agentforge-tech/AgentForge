package cloud.changlu.agentforge.model.registry.models;

import cloud.changlu.agentforge.model.chat.ChatModel;
import cloud.changlu.agentforge.model.chat.StreamingChatModel;
import cloud.changlu.agentforge.model.fim.FimFormat;
import cloud.changlu.agentforge.model.fim.FimModel;
import cloud.changlu.agentforge.model.fim.FimTemplate;
import cloud.changlu.agentforge.model.fim.StreamingFimModel;
import cloud.changlu.agentforge.model.fim.template.FimTemplates;
import cloud.changlu.agentforge.model.fim.template.TemplateFimModel;
import cloud.changlu.agentforge.model.fim.template.TemplateStreamingFimModel;
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
 * @description OpenAI（及 OpenAI-compatible 服务）模型构建
 * @author changlu
 * @date 2026/9/16
 */
public class OpenAiModel extends BaseModel {

    @Override
    public ChatModel buildChatModel(LlmBasicConfig llmBasicConfig) {
        String url = llmBasicConfig.getUrl();
        String modelName = llmBasicConfig.getModelName();
        String apiKey = llmBasicConfig.getApiKey();
        Properties props = llmBasicConfig.getProps();

        OpenAiChatModel.Builder builder =
                OpenAiChatModel.builder().baseUrl(url).modelName(modelName).apiKey(apiKey);

        // 通用参数配置
        configureCommonParams(builder, props);

        return builder.build();
    }

    @Override
    public StreamingChatModel buildStreamChatModel(LlmBasicConfig llmBasicConfig) {
        String url = llmBasicConfig.getUrl();
        String modelName = llmBasicConfig.getModelName();
        String apiKey = llmBasicConfig.getApiKey();
        Properties props = llmBasicConfig.getProps();

        OpenAiStreamingChatModel.Builder builder =
                OpenAiStreamingChatModel.builder().baseUrl(url).modelName(modelName).apiKey(apiKey);

        // 通用参数配置
        configureCommonParams(builder, props);

        return builder.build();
    }

    // 通用参数配置方法
    private void configureCommonParams(OpenAiChatModel.Builder builder, Properties props) {
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

        String returnThinking = props.getProperty(LlmConstant.RETURN_THINKING);
        if (isNotEmpty(returnThinking)) {
            builder.returnThinking(Boolean.parseBoolean(returnThinking));
        }

        String userAgent = props.getProperty(LlmConstant.USER_AGENT);
        if (isNotEmpty(userAgent)) {
            builder.customHeader("User-Agent", userAgent.trim());
        }
    }

    // 重载方法用于 StreamingChatModel
    private void configureCommonParams(OpenAiStreamingChatModel.Builder builder, Properties props) {
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

        String returnThinking = props.getProperty(LlmConstant.RETURN_THINKING);
        if (isNotEmpty(returnThinking)) {
            builder.returnThinking(Boolean.parseBoolean(returnThinking));
        }

        String userAgent = props.getProperty(LlmConstant.USER_AGENT);
        if (isNotEmpty(userAgent)) {
            builder.customHeader("User-Agent", userAgent.trim());
        }
    }

    // ==================== FIM（中间补全）====================

    /**
     * 构造同步 FIM 模型。
     *
     * <p>覆盖所有 OpenAI-compatible 的 completions 服务：Xinference / vLLM / Ollama / TGI 等， 配置 url
     * 即可对接，无需新增 provider 枚举。
     *
     * <p>模板决定走哪条路：
     *
     * <ul>
     *   <li>{@link FimFormat#NATIVE_SUFFIX}：直接返回 Provider，prefix/suffix 由请求体分字段承载；
     *   <li>{@link FimFormat#TOKEN_TEMPLATE}：用 {@link TemplateFimModel} 包装，把 prefix/suffix 按特殊
     *       Token 模板拼成单个 prompt。
     * </ul>
     */
    @Override
    public FimModel buildFimModel(LlmBasicConfig llmBasicConfig) {
        FimTemplate template = resolveFimTemplate(llmBasicConfig);
        OpenAiFimModel raw = buildRawFimModel(llmBasicConfig);
        if (template.format() == FimFormat.NATIVE_SUFFIX) {
            // 原生 suffix 形态不能套装饰器：否则 suffix 会被置空导致后文丢失
            return raw;
        }
        return new TemplateFimModel(raw, template);
    }

    /** 构造流式 FIM 模型，模板处理规则与同步版本一致。 */
    @Override
    public StreamingFimModel buildStreamingFimModel(LlmBasicConfig llmBasicConfig) {
        FimTemplate template = resolveFimTemplate(llmBasicConfig);
        OpenAiStreamingFimModel raw = buildRawStreamingFimModel(llmBasicConfig);
        if (template.format() == FimFormat.NATIVE_SUFFIX) {
            return raw;
        }
        return new TemplateStreamingFimModel(raw, template);
    }

    private OpenAiFimModel buildRawFimModel(LlmBasicConfig llmBasicConfig) {
        OpenAiFimModel.Builder builder =
                OpenAiFimModel.builder()
                        .baseUrl(llmBasicConfig.getUrl())
                        .modelName(llmBasicConfig.getModelName())
                        .apiKey(llmBasicConfig.getApiKey());
        applyFimParams(builder, llmBasicConfig.getProps());
        return builder.build();
    }

    private OpenAiStreamingFimModel buildRawStreamingFimModel(LlmBasicConfig llmBasicConfig) {
        OpenAiStreamingFimModel.Builder builder =
                OpenAiStreamingFimModel.builder()
                        .baseUrl(llmBasicConfig.getUrl())
                        .modelName(llmBasicConfig.getModelName())
                        .apiKey(llmBasicConfig.getApiKey());
        applyFimParams(builder, llmBasicConfig.getProps());
        return builder.build();
    }

    /**
     * 解析 FIM 模板。
     *
     * <p>优先级：显式配置 {@code fimTemplate} &gt; 按模型名推断 &gt; {@code native-suffix} 兜底。
     *
     * <p>显式配置优于推断，是因为特殊 Token 形态会随部署而变（同一模型名的不同权重可能不同）， 而推断错配不会报错，只会静默降质。
     */
    protected FimTemplate resolveFimTemplate(LlmBasicConfig llmBasicConfig) {
        Properties props = llmBasicConfig.getProps();
        if (props != null) {
            String configured = props.getProperty(LlmConstant.FIM_TEMPLATE);
            if (isNotEmpty(configured)) {
                FimTemplate byName = FimTemplates.forName(configured);
                if (byName == null) {
                    throw new IllegalArgumentException(
                            "Unknown fimTemplate: "
                                    + configured
                                    + ", supported: native-suffix / qwen / qwen-no-pipe / qwen-spm"
                                    + " / starcoder2 / code-llama / codestral");
                }
                return byName;
            }
        }
        return FimTemplates.forModel(llmBasicConfig.getModelName());
    }

    private void applyFimParams(OpenAiFimModel.Builder builder, Properties props) {
        applyFimCommon(
                props,
                (connect, read) -> {
                    builder.connectTimeoutMillis(connect);
                    builder.readTimeoutMillis(read);
                },
                builder::temperature,
                builder::topP,
                builder::maxTokens,
                builder::stopSequences,
                builder::disableThinking,
                builder::customHeader);
    }

    private void applyFimParams(OpenAiStreamingFimModel.Builder builder, Properties props) {
        applyFimCommon(
                props,
                (connect, read) -> {
                    builder.connectTimeoutMillis(connect);
                    builder.readTimeoutMillis(read);
                },
                builder::temperature,
                builder::topP,
                builder::maxTokens,
                builder::stopSequences,
                builder::disableThinking,
                builder::customHeader);
    }

    /** 补全场景的超时与参数解析：同步/流式共用，避免两份实现漂移。 */
    private void applyFimCommon(
            Properties props,
            TimeoutApplier timeoutApplier,
            ParamApplier<Double> temperatureApplier,
            ParamApplier<Double> topPApplier,
            ParamApplier<Integer> maxTokensApplier,
            ParamApplier<List<String>> stopApplier,
            ParamApplier<Boolean> thinkingApplier,
            HeaderApplier headerApplier) {

        // 补全默认超时与 chat 不同：本地部署 7B 冷启动常见 5~20s，
        // 沿用 OpenAiFimModel 内置的 2s/3s 会大面积超时。
        int connectTimeoutMillis = DEFAULT_FIM_CONNECT_TIMEOUT_MILLIS;
        int readTimeoutMillis = DEFAULT_FIM_READ_TIMEOUT_MILLIS;
        // OpenAI-compatible 服务没有 DeepSeek 的 thinking 字段，默认不下发；
        // 需要时用 fimDisableThinking 显式开启。
        boolean disableThinking = false;

        if (props != null) {
            String timeout = props.getProperty(LlmConstant.TIMEOUT);
            if (isNotEmpty(timeout)) {
                int timeoutMillis = (int) (Long.parseLong(timeout.trim()) * 1000L);
                connectTimeoutMillis = timeoutMillis;
                readTimeoutMillis = timeoutMillis;
            }
            String temperature = props.getProperty(LlmConstant.TEMPERATURE);
            if (isNotEmpty(temperature)) {
                temperatureApplier.apply(Double.valueOf(Double.parseDouble(temperature.trim())));
            }
            String topP = props.getProperty(LlmConstant.TOP_P);
            if (isNotEmpty(topP)) {
                topPApplier.apply(Double.valueOf(Double.parseDouble(topP.trim())));
            }
            String maxTokens = props.getProperty(LlmConstant.MAX_TOKENS);
            if (isNotEmpty(maxTokens)) {
                maxTokensApplier.apply(Integer.valueOf(Integer.parseInt(maxTokens.trim())));
            }
            String stop = props.getProperty(LlmConstant.STOP);
            if (isNotEmpty(stop)) {
                stopApplier.apply(splitStops(stop));
            }
            String disableThinkingProp = props.getProperty(LlmConstant.FIM_DISABLE_THINKING);
            if (isNotEmpty(disableThinkingProp)) {
                disableThinking = Boolean.parseBoolean(disableThinkingProp.trim());
            }
            String userAgent = props.getProperty(LlmConstant.USER_AGENT);
            if (isNotEmpty(userAgent)) {
                headerApplier.apply("User-Agent", userAgent.trim());
            }
        }

        timeoutApplier.apply(connectTimeoutMillis, readTimeoutMillis);
        thinkingApplier.apply(Boolean.valueOf(disableThinking));
    }

    /** stop 配置按未转义的 {@code |} 分隔；{@code \|} 表示 stop 内部的竖线。 */
    private static List<String> splitStops(String raw) {
        List<String> result = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < raw.length(); i++) {
            char value = raw.charAt(i);
            if (escaped) {
                current.append(value);
                escaped = false;
            } else if (value == '\\') {
                escaped = true;
            } else if (value == '|') {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(value);
            }
        }
        if (escaped) {
            current.append('\\');
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    /** 补全连接超时：本地部署需要比 chat 更宽松。 */
    private static final int DEFAULT_FIM_CONNECT_TIMEOUT_MILLIS = 10_000;

    /** 补全读取超时：7B 本地推理首包常见 5s 以上。 */
    private static final int DEFAULT_FIM_READ_TIMEOUT_MILLIS = 30_000;

    @FunctionalInterface
    private interface TimeoutApplier {
        void apply(int connectTimeoutMillis, int readTimeoutMillis);
    }

    @FunctionalInterface
    private interface ParamApplier<T> {
        void apply(T value);
    }

    @FunctionalInterface
    private interface HeaderApplier {
        void apply(String name, String value);
    }
}
