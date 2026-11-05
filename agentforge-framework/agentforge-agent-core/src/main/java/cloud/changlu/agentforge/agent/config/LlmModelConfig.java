package cloud.changlu.agentforge.agent.config;

/** LLM 模型的上下文与多模态能力配置。Token 上限单位为 K（千 token）。 */
public final class LlmModelConfig {
    private final String baseUrl;
    private final String modelName;
    private final int maxInputKTokens;
    private final int maxOutputKTokens;
    private final boolean supportsText;
    private final boolean supportsImage;

    private LlmModelConfig(Builder builder) {
        this.baseUrl = trimToNull(builder.baseUrl);
        this.modelName = requireText(builder.modelName, "modelName");
        this.maxInputKTokens = requirePositive(builder.maxInputKTokens, "maxInputKTokens");
        this.maxOutputKTokens = requirePositive(builder.maxOutputKTokens, "maxOutputKTokens");
        this.supportsText = builder.supportsText;
        this.supportsImage = builder.supportsImage;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getModelName() {
        return modelName;
    }

    public int getMaxInputKTokens() {
        return maxInputKTokens;
    }

    public int getMaxOutputKTokens() {
        return maxOutputKTokens;
    }

    public int getMaxInputTokens() {
        return Math.multiplyExact(maxInputKTokens, 1000);
    }

    public int getMaxOutputTokens() {
        return Math.multiplyExact(maxOutputKTokens, 1000);
    }

    public boolean isSupportsText() {
        return supportsText;
    }

    public boolean isSupportsImage() {
        return supportsImage;
    }

    public boolean isSupportsMultimodal() {
        return supportsImage;
    }

    private static String requireText(String value, String fieldName) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return trimmed;
    }

    private static int requirePositive(Integer value, String fieldName) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be greater than zero");
        }
        return value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static final class Builder {
        private String baseUrl;
        private String modelName;
        private Integer maxInputKTokens;
        private Integer maxOutputKTokens;
        private boolean supportsText = true;
        private boolean supportsImage;

        private Builder() {}

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder maxInputKTokens(int maxInputKTokens) {
            this.maxInputKTokens = maxInputKTokens;
            return this;
        }

        public Builder maxOutputKTokens(int maxOutputKTokens) {
            this.maxOutputKTokens = maxOutputKTokens;
            return this;
        }

        public Builder supportsText(boolean supportsText) {
            this.supportsText = supportsText;
            return this;
        }

        public Builder supportsImage(boolean supportsImage) {
            this.supportsImage = supportsImage;
            return this;
        }

        public LlmModelConfig build() {
            return new LlmModelConfig(this);
        }
    }
}
