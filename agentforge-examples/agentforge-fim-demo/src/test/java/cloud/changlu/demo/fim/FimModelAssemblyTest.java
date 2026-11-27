package cloud.changlu.demo.fim;

import cloud.changlu.agentforge.model.fim.FimModel;
import cloud.changlu.agentforge.model.fim.FimTemplate;
import cloud.changlu.agentforge.model.fim.template.FimTemplates;
import cloud.changlu.agentforge.model.fim.template.NativeSuffixFimTemplate;
import cloud.changlu.agentforge.model.fim.template.TemplateFimModel;
import cloud.changlu.agentforge.model.registry.LlmFactory;
import cloud.changlu.agentforge.model.registry.config.LlmBasicConfig;
import cloud.changlu.agentforge.model.registry.constant.LlmConstant;
import cloud.changlu.agentforge.model.registry.enums.LlmEnum;
import cloud.changlu.demo.fim.config.FimModelConfiguration;
import cloud.changlu.demo.fim.config.FimProperties;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装配层离线断言：不联网，只验证"配置 → 模型"的映射是否正确。
 *
 * <p>这一组测试的价值在于：Token 模板配错是<b>静默故障</b>（HTTP 200 但补全为空）， 等到真实请求返回空才发现就太晚了。这里把"配置项到底装配成了哪个模板"钉死在断言里。
 *
 * @author changlu
 * @date 2026/11/26
 */
class FimModelAssemblyTest {

    private LlmBasicConfig baseConfig() {
        return LlmBasicConfig.builder()
                .provider(LlmEnum.OPENAI.getCode())
                .url("http://127.0.0.1:9997/v1")
                .modelName("CodeQwen1.5-7B")
                .build();
    }

    /** 未配置模板时，按模型名推断出带竖线的 qwen。 */
    @Test
    void shouldInferQwenTemplateFromModelName() {
        FimModel model = LlmFactory.buildFimModel(baseConfig());

        assertTrue(model instanceof TemplateFimModel, "Qwen 应走模板装饰器");
        assertEquals("qwen", ((TemplateFimModel) model).template().name());
    }

    /** 显式配置必须覆盖推断：这是本部署能正常工作的前提。 */
    @Test
    void shouldOverrideInferenceWithExplicitTemplate() {
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

    /** 无竖线模板的 prompt 与 stop 必须形态自洽，不能混入带竖线的 stop。 */
    @Test
    void shouldKeepNoPipeTemplateSelfConsistent() {
        FimTemplate template = FimTemplates.QWEN_NO_PIPE;

        String prompt = template.buildPrompt("def add(a, b):\n    ", "\n\nassert add(2, 3) == 5\n");

        assertEquals(
                "<fim_prefix>def add(a, b):\n    <fim_suffix>\n\nassert add(2, 3) == 5\n<fim_middle>",
                prompt);
        assertTrue(template.stopTokens().contains("<fim_pad>"));
        assertFalse(template.stopTokens().contains("<|fim_pad|>"), "无竖线模板不应带带竖线的 stop，否则形态自相矛盾");
    }

    /** 未识别的模型不能被硬套 Qwen Token，应直传 prefix + suffix。 */
    @Test
    void shouldNotWrapUnknownModel() {
        LlmBasicConfig config =
                LlmBasicConfig.builder()
                        .provider(LlmEnum.OPENAI.getCode())
                        .url("http://127.0.0.1:8000/v1")
                        .modelName("some-private-coder")
                        .build();

        FimModel model = LlmFactory.buildFimModel(config);

        assertFalse(model instanceof TemplateFimModel, "未识别模型不应被套装饰器");
        assertEquals("native-suffix", NativeSuffixFimTemplate.INSTANCE.name());
    }

    /** 模板名拼错必须立刻报错，不能静默回退。 */
    @Test
    void shouldRejectUnknownTemplateName() {
        LlmBasicConfig config =
                LlmBasicConfig.builder()
                        .provider(LlmEnum.OPENAI.getCode())
                        .url("http://127.0.0.1:9997/v1")
                        .modelName("CodeQwen1.5-7B")
                        .prop(LlmConstant.FIM_TEMPLATE, "qwen-nopipe-typo")
                        .build();

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class, () -> LlmFactory.buildFimModel(config));
        assertTrue(error.getMessage().contains("Unknown fimTemplate"));
    }

    /** 全部合法模板名都能解析出来。 */
    @Test
    void shouldResolveAllSupportedTemplateNames() {
        assertNotNull(FimTemplates.forName("native-suffix"));
        assertNotNull(FimTemplates.forName("qwen"));
        assertNotNull(FimTemplates.forName("qwen-no-pipe"));
        assertNotNull(FimTemplates.forName("qwen-spm"));
        assertNotNull(FimTemplates.forName("starcoder2"));
        assertNotNull(FimTemplates.forName("code-llama"));
        assertNotNull(FimTemplates.forName("codestral"));
        assertNull(FimTemplates.forName("no-such-template"));
    }

    /** 演示工程默认配置应当装配成功，且拿到预期模板。 */
    @Test
    void shouldAssembleDemoDefaultProperties() {
        FimProperties properties = new FimProperties();
        FimModelConfiguration configuration = new FimModelConfiguration();

        FimModel model = configuration.fimModel(properties);

        assertEquals(1, properties.getProviderCode());
        assertEquals("http://127.0.0.1:9997/v1", properties.getBaseUrl());
        assertEquals("CodeQwen1.5-7B", properties.getModelName());
        assertEquals(64, properties.getMaxTokens());
        assertEquals(0.0d, properties.getTemperature());
        assertEquals(
                "<\\|endoftext\\|>|<fim_prefix>|<fim_suffix>|<fim_middle>|<fim_pad>|<file_sep>|\n    }\n",
                properties.getStop());
        assertTrue(model instanceof TemplateFimModel);
        assertEquals(properties.getTemplate(), ((TemplateFimModel) model).template().name());
        assertTrue(((TemplateFimModel) model).template().stopTokens().contains("<|endoftext|>"));
        assertTrue(((TemplateFimModel) model).template().stopTokens().contains("<fim_pad>"));
    }
}
