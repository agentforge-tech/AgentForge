package cloud.changlu.demo.fim.dto;

/**
 * 非流式补全请求体。
 *
 * <p>刻意只暴露 prefix / suffix / language 三项： 采样参数（温度、maxTokens、stop）属于"模型配置"而非"单次请求"， 统一由 {@code
 * FimProperties} 管理，避免前端随意改导致补全质量不稳定。
 *
 * @author changlu
 * @date 2026/11/26
 */
public class CompleteRequest {

    /** 光标之前的文本。 */
    private String prefix;

    /** 光标之后的文本。 */
    private String suffix;

    /** 语言标识，仅作元数据，不下发给模型。 */
    private String language;

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public String getSuffix() {
        return suffix;
    }

    public void setSuffix(String suffix) {
        this.suffix = suffix;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }
}
