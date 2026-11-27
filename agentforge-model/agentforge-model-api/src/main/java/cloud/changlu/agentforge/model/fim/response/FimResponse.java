package cloud.changlu.agentforge.model.fim.response;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 归一化 FIM 响应。
 *
 * <p>与 {@link cloud.changlu.agentforge.model.chat.response.ChatResponse} 的核心差异：
 *
 * <ul>
 *   <li>{@code completion} 是一段裸文本，不再包装成 {@code AiMessage}；
 *   <li>{@code latencyMs} 是一等字段——行内补全的延迟是产品指标，必须能在协议层直接读到。
 * </ul>
 *
 * @author changlu
 * @date 2026/11/25
 */
public final class FimResponse {

    private final String completion;
    private final FimFinishReason finishReason;
    private final FimUsage usage;
    private final String model;
    private final long latencyMs;
    private final Map<String, Object> metadata;

    private FimResponse(Builder builder) {
        this.completion = builder.completion == null ? "" : builder.completion;
        this.finishReason = builder.finishReason;
        this.usage = builder.usage;
        this.model = builder.model;
        this.latencyMs = builder.latencyMs;
        this.metadata =
                Collections.unmodifiableMap(new LinkedHashMap<String, Object>(builder.metadata));
    }

    public static Builder builder() {
        return new Builder();
    }

    public String completion() {
        return completion;
    }

    public FimFinishReason finishReason() {
        return finishReason;
    }

    public FimUsage usage() {
        return usage;
    }

    public String model() {
        return model;
    }

    /** 端到端耗时：FIM 的核心 SLA 指标。 */
    public long latencyMs() {
        return latencyMs;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    /** 补全为空时不值得渲染 Ghost Text。 */
    public boolean isEmpty() {
        return completion.trim().isEmpty();
    }

    public static final class Builder {
        private String completion;
        private FimFinishReason finishReason;
        private FimUsage usage;
        private String model;
        private long latencyMs;
        private final Map<String, Object> metadata = new LinkedHashMap<String, Object>();

        private Builder() {}

        public Builder completion(String completion) {
            this.completion = completion;
            return this;
        }

        public Builder finishReason(FimFinishReason finishReason) {
            this.finishReason = finishReason;
            return this;
        }

        public Builder usage(FimUsage usage) {
            this.usage = usage;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder latencyMs(long latencyMs) {
            this.latencyMs = latencyMs;
            return this;
        }

        public Builder metadata(String key, Object value) {
            if (key != null && value != null) {
                this.metadata.put(key, value);
            }
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            if (metadata != null) {
                this.metadata.putAll(metadata);
            }
            return this;
        }

        public FimResponse build() {
            return new FimResponse(this);
        }
    }
}
