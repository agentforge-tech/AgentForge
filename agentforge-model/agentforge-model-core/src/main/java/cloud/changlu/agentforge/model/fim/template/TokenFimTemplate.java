package cloud.changlu.agentforge.model.fim.template;

import cloud.changlu.agentforge.model.fim.FimFormat;
import cloud.changlu.agentforge.model.fim.FimTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 特殊 Token 型 FIM 模板。
 *
 * <p>支持 PSM（Prefix-Suffix-Middle）与 SPM（Suffix-Prefix-Middle）两种排列。 <b>顺序必须显式声明</b>：顺序错了不会报错，只会静默降质，是
 * FIM 接入中最隐蔽的一类 bug。
 *
 * @author changlu
 * @date 2026/11/25
 */
public final class TokenFimTemplate implements FimTemplate {

    private final String name;
    private final boolean suffixFirst;
    private final String prefixToken;
    private final String suffixToken;
    private final String middleToken;
    private final List<String> extraStops;

    private TokenFimTemplate(Builder builder) {
        this.name = builder.name;
        this.suffixFirst = builder.suffixFirst;
        this.prefixToken = builder.prefixToken;
        this.suffixToken = builder.suffixToken;
        this.middleToken = builder.middleToken;
        this.extraStops = builder.extraStops;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public FimFormat format() {
        return FimFormat.TOKEN_TEMPLATE;
    }

    @Override
    public String buildPrompt(String prefix, String suffix) {
        String safePrefix = prefix == null ? "" : prefix;
        String safeSuffix = suffix == null ? "" : suffix;
        if (suffixFirst) {
            // SPM：后缀 → 前缀 → 中间
            return suffixToken + safeSuffix + prefixToken + safePrefix + middleToken;
        }
        // PSM：前缀 → 后缀 → 中间
        return prefixToken + safePrefix + suffixToken + safeSuffix + middleToken;
    }

    @Override
    public List<String> stopTokens() {
        return extraStops;
    }

    /** 是否为 SPM 排列，便于测试与日志排查。 */
    public boolean suffixFirst() {
        return suffixFirst;
    }

    public static final class Builder {
        private String name = "token-fim";
        private boolean suffixFirst;
        private String prefixToken = "<|fim_prefix|>";
        private String suffixToken = "<|fim_suffix|>";
        private String middleToken = "<|fim_middle|>";
        private List<String> extraStops = Collections.emptyList();

        private Builder() {}

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder suffixFirst(boolean suffixFirst) {
            this.suffixFirst = suffixFirst;
            return this;
        }

        public Builder prefixToken(String prefixToken) {
            this.prefixToken = prefixToken;
            return this;
        }

        public Builder suffixToken(String suffixToken) {
            this.suffixToken = suffixToken;
            return this;
        }

        public Builder middleToken(String middleToken) {
            this.middleToken = middleToken;
            return this;
        }

        public Builder extraStops(List<String> extraStops) {
            this.extraStops = immutable(extraStops);
            return this;
        }

        public TokenFimTemplate build() {
            return new TokenFimTemplate(this);
        }

        private static List<String> immutable(List<String> source) {
            if (source == null || source.isEmpty()) {
                return Collections.emptyList();
            }
            return Collections.unmodifiableList(new ArrayList<String>(source));
        }
    }
}
