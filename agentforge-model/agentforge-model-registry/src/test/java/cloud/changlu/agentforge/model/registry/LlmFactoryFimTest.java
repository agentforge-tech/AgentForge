package cloud.changlu.agentforge.model.registry;

import cloud.changlu.agentforge.model.fim.FimModel;
import cloud.changlu.agentforge.model.fim.StreamingFimModel;
import cloud.changlu.agentforge.model.openai.fim.OpenAiFimModel;
import cloud.changlu.agentforge.model.openai.fim.OpenAiStreamingFimModel;
import cloud.changlu.agentforge.model.registry.config.LlmBasicConfig;
import cloud.changlu.agentforge.model.registry.constant.LlmConstant;
import cloud.changlu.agentforge.model.registry.enums.LlmEnum;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * FIM 装配层测试：验证 LlmFactory 能通过 provider 枚举构建 FIM 能力。
 *
 * <p>不访问真实模型服务。
 *
 * @author changlu
 * @date 2026/11/25
 */
public class LlmFactoryFimTest {

    @Test
    public void shouldBuildDeepSeekFimModel() {
        LlmBasicConfig config =
                LlmBasicConfig.builder()
                        .provider(LlmEnum.DEEPSEEK.getCode())
                        .url("https://api.deepseek.com")
                        .modelName("deepseek-flash")
                        .build();

        FimModel fimModel = LlmFactory.buildFimModel(config);

        assertTrue(fimModel instanceof OpenAiFimModel);
    }

    @Test
    public void shouldBuildDeepSeekStreamingFimModel() {
        LlmBasicConfig config =
                LlmBasicConfig.builder()
                        .provider(LlmEnum.DEEPSEEK.getCode())
                        .url("https://api.deepseek.com/beta")
                        .modelName("deepseek-flash")
                        .build();

        StreamingFimModel streamingFim = LlmFactory.buildStreamingFimModel(config);

        assertTrue(streamingFim instanceof OpenAiStreamingFimModel);
    }

    @Test
    public void shouldAcceptFimPropsWithoutFailing() {
        LlmBasicConfig config =
                LlmBasicConfig.builder()
                        .provider(LlmEnum.DEEPSEEK.getCode())
                        .url("https://api.deepseek.com")
                        .modelName("deepseek-flash")
                        .prop(LlmConstant.MAX_TOKENS, "64")
                        .prop(LlmConstant.TEMPERATURE, "0")
                        .prop(LlmConstant.STOP, "\\n\\n|}\\n")
                        .prop(LlmConstant.TIMEOUT, "2")
                        .prop(LlmConstant.FIM_DISABLE_THINKING, "true")
                        .build();

        assertTrue(LlmFactory.buildFimModel(config) instanceof OpenAiFimModel);
        assertTrue(LlmFactory.buildStreamingFimModel(config) instanceof OpenAiStreamingFimModel);
    }

    @Test
    public void shouldRejectFimForChatOnlyProvider() {
        LlmBasicConfig config =
                LlmBasicConfig.builder()
                        .provider(LlmEnum.ANTHROPIC.getCode())
                        .url("https://api.anthropic.com")
                        .modelName("claude-3-haiku")
                        .build();

        // Anthropic 目前不提供 FIM 能力，应明确抛出而不是静默返回 null
        assertThrows(UnsupportedOperationException.class, () -> LlmFactory.buildFimModel(config));
    }

    @Test
    public void shouldRejectUnknownProvider() {
        LlmBasicConfig config = LlmBasicConfig.builder().provider(999).build();

        assertThrows(IllegalArgumentException.class, () -> LlmFactory.buildFimModel(config));
    }

    @Test
    public void shouldExposeDeepSeekEnumCode() {
        assertSame(LlmEnum.DEEPSEEK, LlmEnum.of(3));
        assertEquals("DeepSeek", LlmEnum.DEEPSEEK.getDesc());
    }
}
