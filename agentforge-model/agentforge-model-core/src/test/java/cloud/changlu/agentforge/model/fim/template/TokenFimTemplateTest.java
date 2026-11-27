package cloud.changlu.agentforge.model.fim.template;

import cloud.changlu.agentforge.model.cancel.CancellationHandle;
import cloud.changlu.agentforge.model.fim.FimFormat;
import cloud.changlu.agentforge.model.fim.FimModel;
import cloud.changlu.agentforge.model.fim.FimTemplate;
import cloud.changlu.agentforge.model.fim.StreamingFimModel;
import cloud.changlu.agentforge.model.fim.request.DefaultFimRequestParameters;
import cloud.changlu.agentforge.model.fim.request.FimRequest;
import cloud.changlu.agentforge.model.fim.response.FimChunk;
import cloud.changlu.agentforge.model.fim.response.FimResponse;
import cloud.changlu.agentforge.model.fim.response.StreamingFimResponseHandler;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link TokenFimTemplate} 与 {@link TemplateFimModel} 测试。
 *
 * @author changlu
 * @date 2026/11/25
 */
public class TokenFimTemplateTest {

    @Test
    public void shouldBuildPsmPrompt() {
        FimTemplate template = FimTemplates.QWEN;

        String prompt = template.buildPrompt("public int add() {", "\n}");

        assertEquals("<|fim_prefix|>public int add() {<|fim_suffix|>\n}<|fim_middle|>", prompt);
        assertEquals(FimFormat.TOKEN_TEMPLATE, template.format());
        assertTrue("必须带上 fim 相关 stop", template.stopTokens().contains("<|fim_pad|>"));
    }

    @Test
    public void shouldBuildSpmPrompt() {
        FimTemplate template = FimTemplates.CODESTRAL;

        String prompt = template.buildPrompt("prefix", "suffix");

        assertEquals("[SUFFIX]suffix[PREFIX]prefix", prompt);
        assertTrue(template.stopTokens().contains("[PREFIX]"));
        assertTrue(template.stopTokens().contains("[SUFFIX]"));
    }

    @Test
    public void shouldHandleNullInputs() {
        String prompt = FimTemplates.QWEN.buildPrompt(null, null);
        assertEquals("<|fim_prefix|><|fim_suffix|><|fim_middle|>", prompt);
    }

    @Test
    public void shouldResolveTemplateByModelName() {
        assertEquals("codestral", FimTemplates.forModel("codestral-latest").name());
        assertEquals("code-llama", FimTemplates.forModel("CodeLlama-7b").name());
        assertEquals("starcoder2", FimTemplates.forModel("bigcode/starcoder2-3b").name());
        assertEquals("qwen", FimTemplates.forModel("Qwen/Qwen2.5-Coder-1.5B").name());
        assertEquals("qwen", FimTemplates.forModel("CodeQwen1.5-7B").name());
    }

    /**
     * 未识别的模型必须兜底为原生 suffix，而不是猜一个特殊 Token 模板。
     *
     * <p>猜错的代价是"接口通、补全空"，比直接放行更难排查。
     */
    @Test
    public void shouldFallBackToNativeSuffixForUnknownModel() {
        assertEquals("native-suffix", FimTemplates.forModel("my-own-model").name());
        assertEquals("native-suffix", FimTemplates.forModel(null).name());
        assertEquals(FimFormat.NATIVE_SUFFIX, FimTemplates.forModel("gpt-unknown").format());
    }

    @Test
    public void shouldResolveTemplateByName() {
        assertSame(FimTemplates.QWEN, FimTemplates.forName("qwen"));
        assertSame(FimTemplates.QWEN_NO_PIPE, FimTemplates.forName("qwen-no-pipe"));
        assertSame(FimTemplates.QWEN_SPM, FimTemplates.forName("QWEN-SPM"));
        assertSame(FimTemplates.STARCODER2, FimTemplates.forName(" starcoder2 "));
        assertSame(FimTemplates.CODE_LLAMA, FimTemplates.forName("codellama"));
        assertSame(FimTemplates.CODESTRAL, FimTemplates.forName("codestral"));
        assertEquals(FimFormat.NATIVE_SUFFIX, FimTemplates.forName("native-suffix").format());
        // 未知名称返回 null，由调用方决定回退或报错
        assertNull(FimTemplates.forName("no-such-template"));
        assertNull(FimTemplates.forName(""));
        assertNull(FimTemplates.forName(null));
    }

    /**
     * 无竖线变体：实测某 Xinference 部署的 CodeQwen1.5-7B 只认这种形态。
     *
     * <p>与 {@link FimTemplates#QWEN} 的唯一差异就是 Token 字面量，顺序同为 PSM。
     */
    @Test
    public void shouldBuildQwenNoPipePrompt() {
        FimTemplate template = FimTemplates.QWEN_NO_PIPE;

        assertEquals(
                "<fim_prefix>def add(a, b):\n    <fim_suffix>\n<fim_middle>",
                template.buildPrompt("def add(a, b):\n    ", "\n"));
        assertEquals(FimFormat.TOKEN_TEMPLATE, template.format());
        assertTrue(template.stopTokens().contains("<fim_pad>"));
        assertFalse("不能混入带竖线的 stop，否则形态自相矛盾", template.stopTokens().contains("<|fim_pad|>"));
    }

    /** 原生 suffix 模板不做任何包装，且不引入额外 stop。 */
    @Test
    public void shouldPassThroughWithNativeSuffixTemplate() {
        FimTemplate template = NativeSuffixFimTemplate.INSTANCE;

        assertEquals("prefix-only", template.buildPrompt("prefix-only", "suffix-ignored"));
        assertEquals("", template.buildPrompt(null, "s"));
        assertEquals(FimFormat.NATIVE_SUFFIX, template.format());
        assertTrue(template.stopTokens().isEmpty());
    }

    /**
     * 回归用例：调用方配置的 stop 不能被模板 stop 覆盖。
     *
     * <p>真实踩坑：某次补全连续吐出多个 Java 方法，排查发现调用方配的 {@code "\n }\n"} 被模板的 {@code <fim_pad>} 整体替换掉了 ——
     * 表现像"stop 没生效"，实际是被模板层吃掉。
     */
    @Test
    public void shouldNotLetTemplateStopsOverrideCallerStops() {
        RecordingModel delegate = new RecordingModel();
        FimModel model = new TemplateFimModel(delegate, FimTemplates.QWEN_NO_PIPE);

        model.complete(
                FimRequest.builder()
                        .prefix("p")
                        .suffix("s")
                        .parameters(
                                DefaultFimRequestParameters.builder()
                                        .stopSequences(Arrays.asList("\n    }\n", "\n\ndef "))
                                        .build())
                        .build());

        List<String> stops = delegate.lastRequest.parameters().stopSequences();
        assertTrue("调用方结构级 stop 必须保留", stops.contains("\n    }\n"));
        assertTrue("调用方结构级 stop 必须保留", stops.contains("\n\ndef "));
        assertTrue("模板 stop 也必须补充进来", stops.contains("<fim_pad>"));
        assertEquals("调用方 stop 应排在前面以保持优先级", 0, stops.indexOf("\n    }\n"));
    }

    @Test
    public void shouldFoldSuffixIntoTemplatePrompt() {
        RecordingModel delegate = new RecordingModel();
        FimModel model = new TemplateFimModel(delegate, FimTemplates.QWEN);

        model.complete(FimRequest.of("pre", "suf"));

        FimRequest forwarded = delegate.lastRequest;
        assertEquals("<|fim_prefix|>pre<|fim_suffix|>suf<|fim_middle|>", forwarded.prefix());
        // suffix 已进入 prompt，底层不再重复发送
        assertEquals("", forwarded.suffix());
    }

    @Test
    public void shouldMergeTemplateStopsWithCallerStops() {
        RecordingModel delegate = new RecordingModel();
        FimModel model = new TemplateFimModel(delegate, FimTemplates.CODESTRAL);

        model.complete(
                FimRequest.builder()
                        .prefix("p")
                        .suffix("s")
                        .parameters(
                                DefaultFimRequestParameters.builder()
                                        .stopSequences(Arrays.asList("\n\n", "[PREFIX]"))
                                        .build())
                        .build());

        List<String> stops = delegate.lastRequest.parameters().stopSequences();
        assertTrue("模板 stop 必须在", stops.contains("[SUFFIX]"));
        assertTrue("调用方 stop 必须在", stops.contains("\n\n"));
        // 去重
        assertEquals(1, Collections.frequency(stops, "[PREFIX]"));
    }

    @Test
    public void shouldPreserveCallerParameters() {
        RecordingModel delegate = new RecordingModel();
        FimModel model = new TemplateFimModel(delegate, FimTemplates.QWEN);

        model.complete(
                FimRequest.builder()
                        .prefix("p")
                        .suffix("s")
                        .parameters(DefaultFimRequestParameters.builder().maxTokens(48).build())
                        .build());

        assertEquals(Integer.valueOf(48), delegate.lastRequest.parameters().maxTokens());
    }

    /** 流式装饰器必须与应用同一套模板变换，否则流式路径会静默丢模板。 */
    @Test
    public void shouldApplyTemplateOnStreamingPath() {
        RecordingStreamingModel delegate = new RecordingStreamingModel();
        StreamingFimModel model =
                new TemplateStreamingFimModel(delegate, FimTemplates.QWEN_NO_PIPE);

        model.complete(FimRequest.of("pre", "suf"), new NoopHandler());

        assertEquals("<fim_prefix>pre<fim_suffix>suf<fim_middle>", delegate.lastRequest.prefix());
        assertEquals("", delegate.lastRequest.suffix());
        assertTrue(delegate.lastRequest.parameters().stopSequences().contains("<fim_pad>"));
    }

    /** 取消必须透传给底层，IDE 场景下用户继续输入时才能真的掐断。 */
    @Test
    public void shouldPropagateCancelOnStreamingPath() {
        RecordingStreamingModel delegate = new RecordingStreamingModel();
        StreamingFimModel model = new TemplateStreamingFimModel(delegate, FimTemplates.QWEN);

        CancellationHandle handle =
                model.completeCancellable(FimRequest.of("p", "s"), new NoopHandler());
        handle.cancel();

        assertTrue("取消必须透传到底层 delegate", delegate.cancelled);
    }

    private static final class RecordingModel implements FimModel {
        private FimRequest lastRequest;

        @Override
        public FimResponse complete(FimRequest request) {
            this.lastRequest = request;
            return FimResponse.builder().completion("ok").build();
        }
    }

    private static final class RecordingStreamingModel implements StreamingFimModel {
        private FimRequest lastRequest;
        private boolean cancelled;

        @Override
        public void complete(FimRequest request, StreamingFimResponseHandler handler) {
            this.lastRequest = request;
        }

        @Override
        public CancellationHandle completeCancellable(
                FimRequest request, StreamingFimResponseHandler handler) {
            this.lastRequest = request;
            return new CancellationHandle() {
                @Override
                public boolean cancel() {
                    cancelled = true;
                    return true;
                }

                @Override
                public boolean isCancelled() {
                    return cancelled;
                }
            };
        }
    }

    private static final class NoopHandler implements StreamingFimResponseHandler {
        @Override
        public void onPartialCompletion(FimChunk chunk) {}

        @Override
        public void onComplete(FimResponse completeResponse) {}

        @Override
        public void onError(Throwable error) {}
    }
}
