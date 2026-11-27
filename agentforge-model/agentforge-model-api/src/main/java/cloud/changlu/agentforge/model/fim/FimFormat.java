package cloud.changlu.agentforge.model.fim;

/**
 * FIM 前/后缀的传递形态。
 *
 * <p>两种形态决定了 Provider 如何发送请求，是模板层与网络层的分界线：
 *
 * @author changlu
 * @date 2026/11/25
 */
public enum FimFormat {

    /**
     * 协议原生支持：{@code prompt} 与 {@code suffix} 分字段发送。
     *
     * <p>适用于 DeepSeek FIM（Beta {@code /completions}）、Mistral Codestral、Ollama {@code
     * /api/generate}。
     */
    NATIVE_SUFFIX,

    /**
     * 协议不支持：由客户端把 prefix/suffix 拼进特殊 Token 模板后用单个 prompt 发送。
     *
     * <p>适用于 Qwen2.5-Coder / Qwen3-Coder、StarCoder2、CodeLlama、CodeGemma 等 以特殊 Token 训练 FIM
     * 能力的开放权重模型。
     */
    TOKEN_TEMPLATE
}
