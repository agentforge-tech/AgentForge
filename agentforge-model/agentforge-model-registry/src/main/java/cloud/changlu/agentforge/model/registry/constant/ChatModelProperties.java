package cloud.changlu.agentforge.model.registry.constant;

import java.util.Properties;

/** 聊天模型通用参数。 */
public final class ChatModelProperties {

    private final Integer timeout;
    private final Integer maxTokens;
    private final Boolean returnThinking;

    private ChatModelProperties(Builder builder) {
        this.timeout = builder.timeout;
        this.maxTokens = builder.maxTokens;
        this.returnThinking = builder.returnThinking;
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
        return properties;
    }

    public static final class Builder {
        private Integer timeout = 60;
        private Integer maxTokens;
        private Boolean returnThinking = false;

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

        public ChatModelProperties build() {
            return new ChatModelProperties(this);
        }
    }
}
