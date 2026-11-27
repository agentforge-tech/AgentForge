package cloud.changlu.agentforge.model.fim.request;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 不可变 FIM 请求。
 *
 * <p>{@code prefix} 与 {@code suffix} 是一等公民——它们是 FIM 的语义本体，不是"两个普通字符串"。 其余字段都是可选上下文，用于后续的仓库级补全。
 *
 * <p>模型名不在此处：它属于模型实例配置，与 {@code ChatModel} 的约定一致，避免每次请求重复传递。
 *
 * @author changlu
 * @date 2026/11/25
 */
public final class FimRequest {

    private final String prefix;
    private final String suffix;
    private final String filePath;
    private final String language;
    private final List<CodeSnippet> context;
    private final FimRequestParameters parameters;
    private final String documentVersion;

    private FimRequest(Builder builder) {
        this.prefix = builder.prefix;
        this.suffix = builder.suffix;
        this.filePath = builder.filePath;
        this.language = builder.language;
        this.context = Collections.unmodifiableList(new ArrayList<CodeSnippet>(builder.context));
        this.parameters = builder.parameters;
        this.documentVersion = builder.documentVersion;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 光标之前的内容，永不为 null。 */
    public String prefix() {
        return prefix;
    }

    /** 光标之后的内容，永不为 null（可能为空串表示位于文件末尾）。 */
    public String suffix() {
        return suffix;
    }

    public String filePath() {
        return filePath;
    }

    public String language() {
        return language;
    }

    public List<CodeSnippet> context() {
        return context;
    }

    public FimRequestParameters parameters() {
        return parameters;
    }

    /** 发起请求时的文档版本号，用于在结果返回时校验是否已过期。 */
    public String documentVersion() {
        return documentVersion;
    }

    /** 极简快捷构造：只有前后缀的最小可用请求。 */
    public static FimRequest of(String prefix, String suffix) {
        return builder().prefix(prefix).suffix(suffix).build();
    }

    /** 上下文代码片段（import、相关方法、最近文件等）。 */
    public static final class CodeSnippet {

        private final String uri;
        private final String content;
        private final String language;

        public CodeSnippet(String uri, String content, String language) {
            this.uri = uri;
            this.content = content == null ? "" : content;
            this.language = language;
        }

        public String uri() {
            return uri;
        }

        public String content() {
            return content;
        }

        public String language() {
            return language;
        }
    }

    public static final class Builder {
        private String prefix = "";
        private String suffix = "";
        private String filePath;
        private String language;
        private final List<CodeSnippet> context = new ArrayList<CodeSnippet>();
        private FimRequestParameters parameters;
        private String documentVersion;

        private Builder() {}

        public Builder prefix(String prefix) {
            this.prefix = prefix == null ? "" : prefix;
            return this;
        }

        public Builder suffix(String suffix) {
            this.suffix = suffix == null ? "" : suffix;
            return this;
        }

        public Builder filePath(String filePath) {
            this.filePath = filePath;
            return this;
        }

        public Builder language(String language) {
            this.language = language;
            return this;
        }

        public Builder context(CodeSnippet snippet) {
            if (snippet != null) {
                this.context.add(snippet);
            }
            return this;
        }

        public Builder context(List<CodeSnippet> snippets) {
            if (snippets != null) {
                this.context.addAll(snippets);
            }
            return this;
        }

        public Builder parameters(FimRequestParameters parameters) {
            this.parameters = parameters;
            return this;
        }

        public Builder documentVersion(String documentVersion) {
            this.documentVersion = documentVersion;
            return this;
        }

        public FimRequest build() {
            Objects.requireNonNull(prefix, "prefix");
            Objects.requireNonNull(suffix, "suffix");
            // 与 ChatRequest 的 messages 非空校验同构：完全空的请求没有补全意义。
            if (prefix.isEmpty() && suffix.isEmpty()) {
                throw new IllegalArgumentException("prefix and suffix must not be both empty");
            }
            return new FimRequest(this);
        }
    }
}
