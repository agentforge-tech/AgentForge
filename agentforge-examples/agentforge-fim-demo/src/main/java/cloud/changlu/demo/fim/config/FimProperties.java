package cloud.changlu.demo.fim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * FIM 对接配置。
 *
 * <p>全部可通过 {@code application.yml} 或环境变量覆盖，便于在客户现场改地址而不改代码。
 *
 * @author changlu
 * @date 2026/11/26
 */
@ConfigurationProperties(prefix = "fim")
public class FimProperties {

    /** 是否启用补全能力。 */
    private boolean enabled = true;

    /** 渠道级参数（Provider、地址、凭证）。 */
    private Channel channel = new Channel();

    /** 模型名，需与 /v1/models 返回的 id 一致。 */
    private String modelName = "CodeQwen1.5-7B";

    /**
     * FIM 特殊 Token 模板名。
     *
     * <p><b>这是接本地模型最关键的一项</b>，必须按部署的 tokenizer 实测结果填写：
     *
     * <ul>
     *   <li>{@code native-suffix}：服务端原生支持 suffix 字段；
     *   <li>{@code qwen}：带竖线 {@code <|fim_prefix|>}（HuggingFace 官方权重）；
     *   <li>{@code qwen-no-pipe}：无竖线 {@code <fim_prefix>}（部分容器化部署权重）；
     *   <li>{@code qwen-spm} / {@code starcoder2} / {@code code-llama} / {@code codestral}。
     * </ul>
     *
     * <p>留空则由模型名推断；推断不出时兜底为 {@code native-suffix}。
     */
    private String template = "qwen-no-pipe";

    /** 采样温度：补全场景建议 0。 */
    private double temperature = 0.0d;

    /** 单次补全最大 token。行内补全 64~128 足够。 */
    private int maxTokens = 64;

    /** 超时（秒）。本地 7B 冷启动常见 5~20s，不要设太小。 */
    private int timeoutSeconds = 30;

    /** 是否使用流式。流式首字更快，体验更好。 */
    private boolean streaming = true;

    /**
     * 结构级停止符，多个用 {@code |} 分隔。
     *
     * <p>实测该模型<b>不会自己吐 {@code <fim_pad>} 收尾</b>，只靠 token 级 stop 会一口气写完多个函数。 真正起作用的是这些结构级 stop。
     */
    private String stop =
            "<\\|endoftext\\|>|<fim_prefix>|<fim_suffix>|<fim_middle>|<fim_pad>|<file_sep>|\n    }\n";

    public Channel getChannel() {
        return channel;
    }

    public void setChannel(Channel channel) {
        this.channel = channel;
    }

    public int getProviderCode() {
        return channel.getProviderCode();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return channel.getBaseUrl();
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getApiKey() {
        return channel.getApiKey();
    }

    public String getTemplate() {
        return template;
    }

    public void setTemplate(String template) {
        this.template = template;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public boolean isStreaming() {
        return streaming;
    }

    public void setStreaming(boolean streaming) {
        this.streaming = streaming;
    }

    public String getStop() {
        return stop;
    }

    public void setStop(String stop) {
        this.stop = stop;
    }

    public static class Channel {

        /** {@code LlmEnum} 的渠道编码；1 表示 OpenAI-compatible。 */
        private int providerCode = 1;

        /** OpenAI-compatible 服务地址，必须带 {@code /v1}。 */
        private String baseUrl = "http://127.0.0.1:9997/v1";

        /** API Key；当前内网服务不校验，使用占位值。 */
        private String apiKey = "EMPTY";

        public int getProviderCode() {
            return providerCode;
        }

        public void setProviderCode(int providerCode) {
            this.providerCode = providerCode;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }
}
