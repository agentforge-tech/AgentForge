package cloud.changlu.agentforge.model.fim;

import java.util.List;

/**
 * 模型家族的 FIM 模板：把 prefix/suffix 变成模型真正看到的字节序列。
 *
 * <p>接口放在 api（协议级抽象），具体模板实现放在 core 的 {@code fim.template} 包。 这样 Provider 只依赖本接口，不会反向依赖 core 的模板实现。
 *
 * <p>重要：不同模型家族的特殊 Token 并不通用，模板选错不会报错，只会静默降质。
 *
 * @author changlu
 * @date 2026/11/25
 */
public interface FimTemplate {

    /** 模板名，用于配置与日志，例如 {@code qwen}、{@code codestral}。 */
    String name();

    FimFormat format();

    /**
     * 构造发给模型的 prompt。
     *
     * <p>{@link FimFormat#NATIVE_SUFFIX} 形态直接返回 {@code prefix}，前后缀交由请求体分字段承载。
     *
     * @param prefix 光标之前的内容
     * @param suffix 光标之后的内容
     * @return 模型真正看到的输入
     */
    String buildPrompt(String prefix, String suffix);

    /**
     * 该模板必须追加的停止符。
     *
     * <p>缺少这些停止符时，模型会继续"写下一个文件"，是 FIM 接入最常见的翻车点。
     */
    List<String> stopTokens();
}
