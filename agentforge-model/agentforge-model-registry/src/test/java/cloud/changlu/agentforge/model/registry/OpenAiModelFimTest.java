package cloud.changlu.agentforge.model.registry;

import cloud.changlu.agentforge.model.fim.FimModel;
import cloud.changlu.agentforge.model.fim.StreamingFimModel;
import cloud.changlu.agentforge.model.fim.template.NativeSuffixFimTemplate;
import cloud.changlu.agentforge.model.fim.template.TemplateFimModel;
import cloud.changlu.agentforge.model.fim.template.TemplateStreamingFimModel;
import cloud.changlu.agentforge.model.registry.config.LlmBasicConfig;
import cloud.changlu.agentforge.model.registry.constant.LlmConstant;
import cloud.changlu.agentforge.model.registry.enums.LlmEnum;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * OpenAI-compatible FIM 装配测试。
 *
 * <p>覆盖两类真实诉求：
 *
 * <ul>
 *   <li>本地 OpenAI-compatible 服务（Xinference / vLLM / Ollama）能否只靠 provider + url 打通；
 *   <li>特殊 Token 形态能否通过 {@code fimTemplate} 显式指定，避免猜错后静默降质。
 * </ul>
 *
 * <p>不访问真实模型服务。
 *
 * @author changlu
 * @date 2026/11/26
 */
public class OpenAiModelFimTest {

    /** 本地 Xinference 部署的典型配置。 */
    private LlmBasicConfig localConfig() {
        return LlmBasicConfig.builder()
                .provider(LlmEnum.OPENAI.getCode())
                .url("http://127.0.0.1:9997/v1")
                .modelName("CodeQwen1.5-7B")
                .build();
    }

    @Test
    public void shouldBuildFimModelForOpenAiCompatibleService() {
        FimModel model = LlmFactory.buildFimModel(localConfig());

        // 模型名含 qwen → 推断为 Qwen 模板 → 走装饰器
        assertTrue(model instanceof TemplateFimModel);
        assertEquals("qwen", ((TemplateFimModel) model).template().name());
    }

    @Test
    public void shouldBuildStreamingFimModelForOpenAiCompatibleService() {
        StreamingFimModel model = LlmFactory.buildStreamingFimModel(localConfig());

        assertTrue(model instanceof TemplateStreamingFimModel);
        assertEquals("qwen", ((TemplateStreamingFimModel) model).template().name());
    }

    /** 显式配置必须覆盖推断结果：这是修复"静默降质"的唯一手段。 */
    @Test
    public void shouldLetExplicitTemplateOverrideInference() {
        LlmBasicConfig config =
                LlmBasicConfig.builder()
                        .provider(LlmEnum.OPENAI.getCode())
                        .url("http://127.0.0.1:9997/v1")
                        .modelName("CodeQwen1.5-7B")
                        .prop(LlmConstant.FIM_TEMPLATE, "qwen-no-pipe")
                        .build();

        TemplateFimModel model = (TemplateFimModel) LlmFactory.buildFimModel(config);

        assertEquals("qwen-no-pipe", model.template().name());
    }

    /** 未识别的模型不应被硬套 Qwen Token，而应直传 prefix + suffix。 */
    @Test
    public void shouldNotWrapUnknownModelWithTokenTemplate() {
        LlmBasicConfig config =
                LlmBasicConfig.builder()
                        .provider(LlmEnum.OPENAI.getCode())
                        .url("http://127.0.0.1:8000/v1")
                        .modelName("my-private-coder")
                        .build();

        FimModel model = LlmFactory.buildFimModel(config);

        // 原生 suffix 形态不套装饰器，直接把 suffix 交给服务端
        assertTrue("未识别模型不应被套装饰器", !(model instanceof TemplateFimModel));
    }

    /** 配置成 native-suffix 时同样不套装饰器。 */
    @Test
    public void shouldNotWrapWhenNativeSuffixConfigured() {
        LlmBasicConfig config =
                LlmBasicConfig.builder()
                        .provider(LlmEnum.OPENAI.getCode())
                        .url("http://127.0.0.1:9997/v1")
                        .modelName("CodeQwen1.5-7B")
                        .prop(LlmConstant.FIM_TEMPLATE, "native-suffix")
                        .build();

        FimModel model = LlmFactory.buildFimModel(config);

        assertTrue(!(model instanceof TemplateFimModel));
        assertEquals("native-suffix", NativeSuffixFimTemplate.INSTANCE.name());
    }

    /** 拼错的模板名必须立刻报错，而不是静默回退成某个默认模板。 */
    @Test
    public void shouldRejectUnknownTemplateName() {
        LlmBasicConfig config =
                LlmBasicConfig.builder()
                        .provider(LlmEnum.OPENAI.getCode())
                        .url("http://127.0.0.1:9997/v1")
                        .modelName("CodeQwen1.5-7B")
                        .prop(LlmConstant.FIM_TEMPLATE, "qwen-nopipe-typo")
                        .build();

        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class, () -> LlmFactory.buildFimModel(config));
        assertTrue(ex.getMessage().contains("Unknown fimTemplate"));
    }

    /**
     * 补全超时可通过 timeout 配置覆盖，且单位为秒。
     *
     * <p>这里只验证装配不抛异常：真实超时值属于 Provider 内部状态， 由 {@code OpenAiFimModelTest} 在协议层断言。
     */
    @Test
    public void shouldAcceptTimeoutPropForFim() {
        LlmBasicConfig config =
                LlmBasicConfig.builder()
                        .provider(LlmEnum.OPENAI.getCode())
                        .url("http://127.0.0.1:9997/v1")
                        .modelName("CodeQwen1.5-7B")
                        .prop(LlmConstant.TIMEOUT, "30")
                        .prop(LlmConstant.MAX_TOKENS, "64")
                        .prop(LlmConstant.TEMPERATURE, "0")
                        .prop(LlmConstant.STOP, "<fim_pad>|<|endoftext|>")
                        .build();

        assertTrue(LlmFactory.buildFimModel(config) instanceof TemplateFimModel);
        assertTrue(LlmFactory.buildStreamingFimModel(config) instanceof TemplateStreamingFimModel);
    }
}
