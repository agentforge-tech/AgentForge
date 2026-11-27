package cloud.changlu.agentforge.model.fim.request;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link FimRequestParameters} 的默认实现与合并逻辑。
 *
 * <p>位置与 {@link cloud.changlu.agentforge.model.chat.request.DefaultChatRequestParameters} 一致： 契约在
 * api，默认实现在 core。
 *
 * @author changlu
 * @date 2026/11/25
 */
public final class DefaultFimRequestParameters implements FimRequestParameters {

    private final Integer maxTokens;
    private final Double temperature;
    private final Double topP;
    private final List<String> stopSequences;
    private final Map<String, Object> customParameters;

    private DefaultFimRequestParameters(Builder builder) {
        this.maxTokens = builder.maxTokens;
        this.temperature = builder.temperature;
        this.topP = builder.topP;
        this.stopSequences =
                builder.stopSequences == null
                        ? Collections.<String>emptyList()
                        : Collections.unmodifiableList(
                                new ArrayList<String>(builder.stopSequences));
        this.customParameters =
                Collections.unmodifiableMap(
                        new LinkedHashMap<String, Object>(builder.customParameters));
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 请求级参数覆盖模型级默认值；{@code null} 表示不覆盖。
     *
     * <p>语义与 {@code DefaultChatRequestParameters.merge} 基本一致，<b>但 stop 是例外</b>： stop
     * 取<b>并集</b>而不是覆盖。
     *
     * <p>原因：stop 是"任一命中即停止"的叠加约束，多个 stop 是并列关系而非替换关系。 若按覆盖语义处理，模板层注入的 {@code <fim_pad>} 会把调用方配置的结构级
     * stop （例如 {@code "\n }\n"}）整体顶掉，模型于是连续吐出多个方法 —— 表现像"stop 没生效"，实际是被覆盖掉了。
     */
    public static DefaultFimRequestParameters merge(
            FimRequestParameters defaults, FimRequestParameters overrides) {
        Builder builder = builder();
        apply(builder, defaults);
        apply(builder, overrides);
        // stop 单独做并集：defaults 在前以保持优先级，overrides 补充在后
        builder.stopSequences(unionStops(defaults, overrides));
        return builder.build();
    }

    /** 合并两边的 stop，去重且保持 defaults 优先的顺序。 */
    private static List<String> unionStops(
            FimRequestParameters defaults, FimRequestParameters overrides) {
        List<String> merged = new ArrayList<String>();
        collectStops(merged, defaults == null ? null : defaults.stopSequences());
        collectStops(merged, overrides == null ? null : overrides.stopSequences());
        return merged;
    }

    private static void collectStops(List<String> target, List<String> source) {
        if (source == null) {
            return;
        }
        for (String stop : source) {
            if (stop != null && !stop.isEmpty() && !target.contains(stop)) {
                target.add(stop);
            }
        }
    }

    private static void apply(Builder builder, FimRequestParameters parameters) {
        if (parameters == null) {
            return;
        }
        if (parameters.maxTokens() != null) {
            builder.maxTokens(parameters.maxTokens());
        }
        if (parameters.temperature() != null) {
            builder.temperature(parameters.temperature());
        }
        if (parameters.topP() != null) {
            builder.topP(parameters.topP());
        }
        if (parameters.stopSequences() != null && !parameters.stopSequences().isEmpty()) {
            builder.stopSequences(parameters.stopSequences());
        }
        if (parameters.customParameters() != null && !parameters.customParameters().isEmpty()) {
            builder.customParameters(parameters.customParameters());
        }
    }

    @Override
    public Integer maxTokens() {
        return maxTokens;
    }

    @Override
    public Double temperature() {
        return temperature;
    }

    @Override
    public Double topP() {
        return topP;
    }

    @Override
    public List<String> stopSequences() {
        return stopSequences;
    }

    @Override
    public Map<String, Object> customParameters() {
        return customParameters;
    }

    public static final class Builder {
        private Integer maxTokens;
        private Double temperature;
        private Double topP;
        private List<String> stopSequences;
        private final Map<String, Object> customParameters = new LinkedHashMap<String, Object>();

        private Builder() {}

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder topP(Double topP) {
            this.topP = topP;
            return this;
        }

        public Builder stopSequences(List<String> stopSequences) {
            this.stopSequences = stopSequences;
            return this;
        }

        public Builder customParameter(String key, Object value) {
            if (key != null && value != null) {
                this.customParameters.put(key, value);
            }
            return this;
        }

        public Builder customParameters(Map<String, Object> customParameters) {
            if (customParameters != null) {
                this.customParameters.putAll(customParameters);
            }
            return this;
        }

        public DefaultFimRequestParameters build() {
            return new DefaultFimRequestParameters(this);
        }
    }
}
