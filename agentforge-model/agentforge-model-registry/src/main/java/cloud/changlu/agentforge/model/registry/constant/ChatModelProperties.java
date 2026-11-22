package cloud.changlu.agentforge.model.registry.constant;

import java.util.Properties;

/** 聊天模型通用参数。 */
public final class ChatModelProperties {

    private final Integer timeout;
    private final Integer maxTokens;
    private final Boolean returnThinking;
    private final String userAgent;

    private ChatModelProperties(Builder builder) {
        this.timeout = builder.timeout;
        this.maxTokens = builder.maxTokens;
        this.returnThinking = builder.returnThinking;
        this.userAgent = builder.userAgent;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Properties toProperties() {
        Properties properties = new Properties();
        if (timeout != null) {
            properties.setProperty(LlmConstant.TIMEOUT, String.valueOf(timeout));
        }
        if (maxTokens != null) {
            properties.setProperty(LlmConstant.MAX_TOKENS, String.valueOf(maxTokens));
        }
        if (returnThinking != null) {
            properties.setProperty(LlmConstant.RETURN_THINKING, String.valueOf(returnThinking));
        }
        if (userAgent != null && !userAgent.trim().isEmpty()) {
            properties.setProperty(LlmConstant.USER_AGENT, userAgent.trim());
        }
        return properties;
    }

    public static final class Builder {
        private Integer timeout = 60;
        private Integer maxTokens;
        private Boolean returnThinking = false;
        private String userAgent;

        private Builder() {}

        public Builder timeout(Integer timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            if (maxTokens != null) {
                this.maxTokens = maxTokens;
            }
            return this;
        }

        public Builder returnThinking(Boolean returnThinking) {
            if (returnThinking != null) {
                this.returnThinking = returnThinking;
            }
            return this;
        }

        /**
         * 设置客户端 User-Agent。为空时由 framework transport 使用内置值。
         *
         * <p>用于标识真实调用方，并规避 Cloudflare 对 JDK 默认 UA 的 BIC 拦截（403/1010）。
         */
        public Builder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public ChatModelProperties build() {
            return new ChatModelProperties(this);
        }
    }
}
