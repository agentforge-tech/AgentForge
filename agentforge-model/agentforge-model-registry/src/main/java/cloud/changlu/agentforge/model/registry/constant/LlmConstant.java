package cloud.changlu.agentforge.model.registry.constant;

/**
 * @description llm相关配置参数
 * @author changlu
 * @date 2026/9/16
 */
public interface LlmConstant {

    // 通用参数
    // 秒为单位
    String TIMEOUT = "timeout";
    String TEMPERATURE = "temperature";
    String TOP_P = "topP";
    String MAX_TOKENS = "maxTokens";
    String RETURN_THINKING = "returnThinking";

    // 客户端标识，未设置时由 framework transport 兜底。部分网关会拒绝 JDK 默认的 Java/1.x 签名
    String USER_AGENT = "userAgent";

    // FIM 补全相关参数
    /** 停止符，多个值用 {@code |} 分隔。行内补全必须设置，否则模型可能一次吐出整个类。 */
    String STOP = "stop";

    /**
     * 是否关闭思考模式，默认 true。
     *
     * <p>DeepSeek FIM 仅支持非思考模式，而思考模式默认开启，因此必须显式关闭。
     */
    String FIM_DISABLE_THINKING = "fimDisableThinking";

    /**
     * 百炼 Partial Mode 专用：是否把 suffix 折叠进提示词做弱约束，默认 true。
     *
     * <p>百炼没有原生 suffix，该开关只影响"是否额外告知模型后文"，不等于 FIM 能力。
     */
    String FIM_SUFFIX_HINT = "fimSuffixHint";

    /**
     * FIM 特殊 Token 模板名，不配置则按模型名推断，无法识别时兜底为 {@code native-suffix}。
     *
     * <p>可选值：{@code native-suffix} / {@code qwen} / {@code qwen-no-pipe} / {@code qwen-spm} /
     * {@code starcoder2} / {@code code-llama} / {@code codestral}。
     *
     * <p><b>为什么需要显式配置</b>：特殊 Token 形态并不只由模型家族决定，还取决于部署时加载的 {@code
     * tokenizer_config.json}。同一模型名的不同权重可能注册了不同的 special token （例如实测到某 Xinference 部署的 CodeQwen1.5-7B
     * 只注册了无竖线形态）， 而<b>错配不会报错</b>，只会表现为补全为空或静默降质。
     *
     * <p>判定方法：请求 {@code prompt="<fim_prefix><fim_suffix><fim_middle>"}， 若 {@code
     * usage.prompt_tokens == 3} 则为无竖线形态，用 {@code qwen-no-pipe}。
     */
    String FIM_TEMPLATE = "fimTemplate";
}
