package cloud.changlu.agentforge.model.fim.template;

import cloud.changlu.agentforge.model.fim.FimFormat;
import cloud.changlu.agentforge.model.fim.FimTemplate;

import java.util.Collections;
import java.util.List;

/**
 * 原生 suffix 形态的 FIM 模板：不做任何 Token 包装。
 *
 * <p>用于两类场景：
 *
 * <ul>
 *   <li>服务端原生支持 {@code prompt} + {@code suffix} 两个字段（DeepSeek Beta /completions、 Mistral {@code
 *       /v1/fim/completions}）；
 *   <li>模型家族无法识别时的<b>安全兜底</b>。
 * </ul>
 *
 * <p><b>为什么兜底要用它，而不是默认套 Qwen 模板</b>：特殊 Token 形态随部署而异，猜错不会报错， 只会静默降质甚至返回空补全。直传 prefix + suffix
 * 让服务端自己决定，最坏情况是"没有 FIM 能力"， 而不是"看起来能跑但结果是错的"。
 *
 * <p><b>注意</b>：本模板只能直接用在原始 Provider 上，<b>不能</b>经由 {@link TemplateFimModel} 装饰 —— 后者会把 suffix
 * 置空，导致后文丢失。
 *
 * @author changlu
 * @date 2026/11/26
 */
public final class NativeSuffixFimTemplate implements FimTemplate {

    /** 无状态，复用单例。 */
    public static final NativeSuffixFimTemplate INSTANCE = new NativeSuffixFimTemplate();

    private NativeSuffixFimTemplate() {}

    @Override
    public String name() {
        return "native-suffix";
    }

    @Override
    public FimFormat format() {
        return FimFormat.NATIVE_SUFFIX;
    }

    /** 原样返回 prefix：suffix 由请求体的独立字段承载。 */
    @Override
    public String buildPrompt(String prefix, String suffix) {
        return prefix == null ? "" : prefix;
    }

    /** 原生 suffix 形态不依赖任何特殊 Token 收尾。 */
    @Override
    public List<String> stopTokens() {
        return Collections.emptyList();
    }
}
