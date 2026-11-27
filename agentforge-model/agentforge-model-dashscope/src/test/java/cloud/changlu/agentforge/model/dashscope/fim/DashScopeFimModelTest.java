package cloud.changlu.agentforge.model.dashscope.fim;

import cloud.changlu.agentforge.model.exception.ModelException;
import cloud.changlu.agentforge.model.fim.FimModel;
import cloud.changlu.agentforge.model.fim.request.DefaultFimRequestParameters;
import cloud.changlu.agentforge.model.fim.request.FimRequest;
import cloud.changlu.agentforge.model.fim.response.FimFinishReason;
import cloud.changlu.agentforge.model.fim.response.FimResponse;
import cloud.changlu.agentforge.model.http.HttpRequest;
import cloud.changlu.agentforge.model.http.HttpResponse;
import cloud.changlu.agentforge.model.http.HttpTransport;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link DashScopeFimModel} 测试：验证 Partial Mode 的 wire 形状。
 *
 * <p>重点断言"最后一条消息必须是 assistant + partial=true"，这是百炼前缀续写的触发条件， 写错不会报错、只会退化成普通对话生成。
 *
 * @author changlu
 * @date 2026/11/25
 */
public class DashScopeFimModelTest {

    @Test
    public void shouldPostPartialAssistantMessageToChatCompletions() {
        AtomicReference<HttpRequest> captured = new AtomicReference<HttpRequest>();
        HttpTransport transport =
                request -> {
                    captured.set(request);
                    return new HttpResponse(200, SUCCESS_BODY);
                };

        FimModel model =
                DashScopeFimModel.builder()
                        .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1/")
                        .modelName("qwen3-coder-flash")
                        .httpTransport(transport)
                        .build();

        model.complete(
                FimRequest.builder()
                        .prefix("public int add(int a, int b) {\n")
                        .suffix("\n}")
                        .build());

        HttpRequest request = captured.get();
        assertNotNull(request);
        // 必须走 /chat/completions，不是 /completions
        assertEquals(
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                request.url());
        assertNull(request.headers().get("Authorization"));

        String body = request.body();
        assertTrue("必须使用 messages 而不是 prompt", body.contains("\"messages\""));
        assertFalse(body.contains("\"prompt\""));
        // 关键：最后一条必须是 assistant + partial=true
        assertTrue(body.contains("\"role\":\"assistant\""));
        assertTrue(body.contains("\"partial\":true"));
        assertTrue("前缀应作为 assistant 的 content", body.contains("public int add(int a, int b)"));
        assertTrue(body.contains("\"stream\":false"));
    }

    @Test
    public void shouldNotSendSuffixHintByDefault() {
        AtomicReference<HttpRequest> captured = new AtomicReference<HttpRequest>();
        HttpTransport transport =
                request -> {
                    captured.set(request);
                    return new HttpResponse(200, SUCCESS_BODY);
                };

        FimModel model = DashScopeFimModel.builder().httpTransport(transport).build();

        model.complete(FimRequest.builder().prefix("int a = 1;\n").suffix("\nreturn a;").build());

        String body = captured.get().body();
        // 实测：携带 suffix 提示会让模型在长上下文下返回空内容，因此默认关闭
        assertFalse("默认不应携带 suffix 提示", body.contains("光标之后已存在"));
        assertFalse("默认不应出现 system 消息", body.contains("\"role\":\"system\""));
    }

    /** 打开开关时，suffix 必须放 system（放 user 实测会触发空返回）。 */
    @Test
    public void shouldSendSuffixHintAsSystemMessageWhenEnabled() {
        AtomicReference<HttpRequest> captured = new AtomicReference<HttpRequest>();
        HttpTransport transport =
                request -> {
                    captured.set(request);
                    return new HttpResponse(200, SUCCESS_BODY);
                };

        FimModel model =
                DashScopeFimModel.builder()
                        .suffixHintEnabled(true)
                        .httpTransport(transport)
                        .build();

        model.complete(FimRequest.builder().prefix("int a = 1;\n").suffix("\nreturn a;").build());

        String body = captured.get().body();
        assertTrue("应携带 system 消息", body.contains("\"role\":\"system\""));
        assertTrue("suffix 应出现在 system 内容里", body.contains("光标之后已存在"));
    }

    @Test
    public void shouldSkipSuffixHintWhenDisabled() {
        AtomicReference<HttpRequest> captured = new AtomicReference<HttpRequest>();
        HttpTransport transport =
                request -> {
                    captured.set(request);
                    return new HttpResponse(200, SUCCESS_BODY);
                };

        FimModel model =
                DashScopeFimModel.builder()
                        .suffixHintEnabled(false)
                        .httpTransport(transport)
                        .build();

        model.complete(FimRequest.builder().prefix("int a = 1;\n").suffix("\nreturn a;").build());

        assertFalse("关闭后应是纯前缀续写，不再提及后文", captured.get().body().contains("光标之后已存在"));
    }

    @Test
    public void shouldParseMessageContentAndUsage() {
        HttpTransport transport = request -> new HttpResponse(200, SUCCESS_BODY);
        FimModel model = DashScopeFimModel.builder().httpTransport(transport).build();

        FimResponse response = model.complete(FimRequest.of("a", "b"));

        assertEquals("    return a + b;", response.completion());
        assertEquals(FimFinishReason.STOP, response.finishReason());
        assertEquals("qwen3-coder-flash", response.model());
        assertNotNull(response.usage());
        assertEquals(48L, response.usage().inputTokens());
        assertEquals(19L, response.usage().outputTokens());
        assertEquals(0L, response.usage().cachedInputTokens());
    }

    @Test
    public void shouldCarryDefaultsStopAndParameters() {
        AtomicReference<HttpRequest> captured = new AtomicReference<HttpRequest>();
        HttpTransport transport =
                request -> {
                    captured.set(request);
                    return new HttpResponse(200, SUCCESS_BODY);
                };

        FimModel model =
                DashScopeFimModel.builder().maxTokens(128).httpTransport(transport).build();

        model.complete(
                FimRequest.builder()
                        .prefix("a")
                        .suffix("b")
                        .parameters(
                                DefaultFimRequestParameters.builder()
                                        .maxTokens(64)
                                        .stopSequences(Arrays.asList("\n\n", "}\n"))
                                        .build())
                        .build());

        String body = captured.get().body();
        assertTrue(body.contains("\"max_tokens\":64"));
        assertTrue(body.contains("\"stop\""));
        assertTrue(body.contains("}\\n"));
    }

    @Test
    public void shouldDefaultToBailianBaseAndCoderModel() {
        AtomicReference<HttpRequest> captured = new AtomicReference<HttpRequest>();
        HttpTransport transport =
                request -> {
                    captured.set(request);
                    return new HttpResponse(200, SUCCESS_BODY);
                };

        FimModel model = DashScopeFimModel.builder().httpTransport(transport).build();
        model.complete(FimRequest.of("a", "b"));

        assertEquals(
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                captured.get().url());
        assertTrue(captured.get().body().contains("\"model\":\"qwen3-coder-flash\""));
    }

    @Test
    public void shouldMapFinishReasons() {
        assertEquals(FimFinishReason.STOP, DashScopePartialWire.mapFinishReason("stop"));
        assertEquals(FimFinishReason.LENGTH, DashScopePartialWire.mapFinishReason("length"));
        assertEquals(
                FimFinishReason.CONTENT_FILTER,
                DashScopePartialWire.mapFinishReason("content_filter"));
        assertEquals(FimFinishReason.OTHER, DashScopePartialWire.mapFinishReason("weird"));
        assertNull(DashScopePartialWire.mapFinishReason(null));
    }

    @Test
    public void shouldSurfaceHttpError() {
        HttpTransport transport =
                request ->
                        new HttpResponse(
                                401, "{\"error\":{\"message\":\"upstream rejected request\"}}");
        FimModel model = DashScopeFimModel.builder().httpTransport(transport).build();

        ModelException error =
                assertThrows(ModelException.class, () -> model.complete(FimRequest.of("a", "b")));
        assertEquals(Integer.valueOf(401), error.statusCode());
        assertTrue(error.responseBody().contains("upstream rejected request"));
    }

    @Test
    public void shouldWrapIoException() {
        HttpTransport transport =
                request -> {
                    throw new IOException("boom");
                };
        FimModel model = DashScopeFimModel.builder().httpTransport(transport).build();

        assertThrows(ModelException.class, () -> model.complete(FimRequest.of("a", "b")));
    }

    @Test
    public void shouldRejectBlankModelName() {
        assertThrows(
                IllegalStateException.class,
                () -> DashScopeFimModel.builder().modelName(" ").build());
    }

    @Test
    public void shouldExposeBailianShortcut() {
        assertNotNull(DashScopeFimModel.builder().build());
    }

    private static final String SUCCESS_BODY =
            "{\"id\":\"chatcmpl-d103b1cf-4bda-942f-92d6-d7ecabfeeccb\","
                    + "\"object\":\"chat.completion\","
                    + "\"model\":\"qwen3-coder-flash\","
                    + "\"choices\":[{\"index\":0,\"logprobs\":null,\"finish_reason\":\"stop\","
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"    return a + b;\"}}],"
                    + "\"usage\":{\"prompt_tokens\":48,\"completion_tokens\":19,\"total_tokens\":67,"
                    + "\"prompt_tokens_details\":{\"cache_type\":\"implicit\",\"cached_tokens\":0}},"
                    + "\"created\":1756800231,\"system_fingerprint\":null}";
}
