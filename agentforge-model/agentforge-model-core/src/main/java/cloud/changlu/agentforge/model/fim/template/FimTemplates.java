package cloud.changlu.agentforge.model.fim.template;

import cloud.changlu.agentforge.model.fim.FimTemplate;

import java.util.Arrays;
import java.util.Collections;

/**
 * 常用模型家族的 FIM 模板。
 *
 * <p>特殊 Token 不通用，必须按模型选用。模板来源见各常量注释，与 Continue 开源实现 {@code
 * core/autocomplete/templating/AutocompleteTemplate.ts} 保持一致。
 *
 * @author changlu
 * @date 2026/11/25
 */
public final class FimTemplates {

    private FimTemplates() {}

    /**
     * Qwen2.5-Coder / Qwen3-Coder。
     *
     * <p>Qwen 官方 README 的 FIM 示例使用 PSM： {@code <|fim_prefix|>P<|fim_suffix|>S<|fim_middle|>}。
     */
    public static final FimTemplate QWEN =
            TokenFimTemplate.builder()
                    .name("qwen")
                    .prefixToken("<|fim_prefix|>")
                    .suffixToken("<|fim_suffix|>")
                    .middleToken("<|fim_middle|>")
                    .extraStops(
                            Arrays.asList(
                                    "<|endoftext|>",
                                    "<|fim_pad|>",
                                    "<|repo_name|>",
                                    "<|file_sep|>",
                                    "<|im_start|>",
                                    "<|im_end|>"))
                    .build();

    /**
     * Qwen 特殊 Token 的另一种形态：<b>无竖线</b> {@code <fim_prefix>}。
     *
     * <p>部分部署权重的 {@code tokenizer_config.json} 里 {@code additional_special_tokens}
     * 注册的是无竖线形态（实测案例：Xinference + HAMi 容器化加载的 CodeQwen1.5-7B）。 此时必须用本模板，否则标记会被当普通文本切词。
     *
     * <p><b>怎么判断该用哪个</b>：发 {@code prompt="<fim_prefix><fim_suffix><fim_middle>"}， {@code
     * usage.prompt_tokens == 3} 用本模板；若是 3 的若干倍（通常 20+）， 说明标记未被识别为特殊 Token，应改用 {@link #QWEN}。
     *
     * <p><b>错配不会报错</b>：表现为 HTTP 200、补全为空或静默降质，只能靠上述方法自测。
     */
    public static final FimTemplate QWEN_NO_PIPE =
            TokenFimTemplate.builder()
                    .name("qwen-no-pipe")
                    .prefixToken("<fim_prefix>")
                    .suffixToken("<fim_suffix>")
                    .middleToken("<fim_middle>")
                    .extraStops(Arrays.asList("<fim_pad>", "<|endoftext|>"))
                    .build();

    /**
     * Qwen 的 SPM 变体。
     *
     * <p>部分微调权重按 SPM 训练，此时必须切换到该模板，否则效果下降但不报错。
     */
    public static final FimTemplate QWEN_SPM =
            TokenFimTemplate.builder()
                    .name("qwen-spm")
                    .suffixFirst(true)
                    .prefixToken("<|fim_prefix|>")
                    .suffixToken("<|fim_suffix|>")
                    .middleToken("<|fim_middle|>")
                    .extraStops(
                            Arrays.asList(
                                    "<|endoftext|>",
                                    "<|fim_pad|>",
                                    "<|repo_name|>",
                                    "<|file_sep|>"))
                    .build();

    /** StarCoder2 / Stable Code：无竖线的 {@code <fim_*>} Token。 */
    public static final FimTemplate STARCODER2 =
            TokenFimTemplate.builder()
                    .name("starcoder2")
                    .prefixToken("<fim_prefix>")
                    .suffixToken("<fim_suffix>")
                    .middleToken("<fim_middle>")
                    .extraStops(Arrays.asList("<file_sep>", "<|endoftext|>"))
                    .build();

    /** CodeLlama：{@code <PRE>} / {@code <SUF>} / {@code <MID>}，前后各带空格。 */
    public static final FimTemplate CODE_LLAMA =
            TokenFimTemplate.builder()
                    .name("code-llama")
                    .prefixToken("<PRE> ")
                    .suffixToken(" <SUF>")
                    .middleToken(" <MID>")
                    .extraStops(Collections.singletonList("<EOT>"))
                    .build();

    /**
     * Codestral 的模板方式（SPM 顺序）。
     *
     * <p>注意：这是"用 completions 端点 + 模板"的路径。若使用 Mistral 官方 {@code /v1/fim/completions}，应改用
     * NATIVE_SUFFIX 形态，不需要本模板。
     */
    public static final FimTemplate CODESTRAL =
            TokenFimTemplate.builder()
                    .name("codestral")
                    .suffixFirst(true)
                    .prefixToken("[PREFIX]")
                    .suffixToken("[SUFFIX]")
                    .middleToken("")
                    .extraStops(Arrays.asList("[PREFIX]", "[SUFFIX]"))
                    .build();

    /**
     * 按模型名推断模板。
     *
     * <p>仅在无法显式配置时兜底使用；生产环境建议显式指定，避免上游改名导致模板错配。
     *
     * <p><b>未识别时返回 {@link NativeSuffixFimTemplate} 而不是 Qwen 模板</b>： 特殊 Token
     * 形态随部署而异，猜错不会报错、只会静默降质。直传 prefix + suffix 让服务端 自行决定，最坏情况是"该模型没有 FIM 能力"，而不是"看起来能跑但结果是错的"。
     *
     * @param modelName 模型标识，大小写不敏感
     * @return 匹配到的模板；无法识别时返回 {@code native-suffix}
     */
    public static FimTemplate forModel(String modelName) {
        if (modelName == null) {
            return NativeSuffixFimTemplate.INSTANCE;
        }
        String lower = modelName.toLowerCase();
        if (lower.contains("codestral")) {
            return CODESTRAL;
        }
        if (lower.contains("codellama") || lower.contains("code-llama")) {
            return CODE_LLAMA;
        }
        if (lower.contains("starcoder") || lower.contains("stable-code")) {
            return STARCODER2;
        }
        if (lower.contains("qwen")) {
            return QWEN;
        }
        return NativeSuffixFimTemplate.INSTANCE;
    }

    /**
     * 按模板名精确查找，供配置项 {@code fimTemplate} 使用。
     *
     * <p>支持的名称：{@code native-suffix}、{@code qwen}、{@code qwen-no-pipe}、 {@code qwen-spm}、{@code
     * starcoder2}、{@code code-llama}、{@code codestral}。
     *
     * @param name 模板名，大小写与前后空白不敏感
     * @return 匹配到的模板；无法识别时返回 {@code null}，由调用方决定回退策略
     */
    public static FimTemplate forName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        String key = name.trim().toLowerCase();
        if ("native-suffix".equals(key) || "native".equals(key)) {
            return NativeSuffixFimTemplate.INSTANCE;
        }
        if ("qwen".equals(key)) {
            return QWEN;
        }
        if ("qwen-no-pipe".equals(key) || "qwen-nopipe".equals(key)) {
            return QWEN_NO_PIPE;
        }
        if ("qwen-spm".equals(key)) {
            return QWEN_SPM;
        }
        if ("starcoder2".equals(key)) {
            return STARCODER2;
        }
        if ("code-llama".equals(key) || "codellama".equals(key)) {
            return CODE_LLAMA;
        }
        if ("codestral".equals(key)) {
            return CODESTRAL;
        }
        return null;
    }
}
