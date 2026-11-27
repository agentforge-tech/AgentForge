package cloud.changlu.agentforge.model.openai.fim;

import cloud.changlu.agentforge.model.exception.ModelException;
import cloud.changlu.agentforge.model.fim.FimCall;
import cloud.changlu.agentforge.model.fim.FimModel;
import cloud.changlu.agentforge.model.fim.request.DefaultFimRequestParameters;
import cloud.changlu.agentforge.model.fim.request.FimRequest;
import cloud.changlu.agentforge.model.fim.response.FimFinishReason;
import cloud.changlu.agentforge.model.fim.response.FimResponse;
import cloud.changlu.agentforge.model.http.HttpRequest;
import cloud.changlu.agentforge.model.http.HttpResponse;
import cloud.changlu.agentforge.model.http.HttpTransport;
import cloud.changlu.agentforge.model.openai.OpenAiFimModel;
import cloud.changlu.agentforge.model.openai.OpenAiFimWire;

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
 * {@link OpenAiFimModel} 单元测试。
 *
 * @author changlu
 * @date 2026/11/25
 */
public class OpenAiFimModelTest {

    // ==================== 协议映射：请求侧 ====================

    @Test
    public void shouldPostPromptAndSuffixToBetaCompletions() {
        AtomicReference<HttpRequest> captured = new AtomicReference<HttpRequest>();
        HttpTransport transport =
                request -> {
                    captured.set(request);
                    return new HttpResponse(200, SUCCESS_BODY);
                };

        FimModel model =
                OpenAiFimModel.builder()
                        .baseUrl("https://api.deepseek.com/beta/")
                        .modelName("deepseek-flash")
                        .httpTransport(transport)
                        .build();

        model.complete(
                FimRequest.builder()
                        .prefix("public int add(int a, int b) {\n")
                        .suffix("\n}")
                        .build());

        HttpRequest request = captured.get();
        assertNotNull(request);
        // baseUrl 尾部斜杠应被裁掉，且必须走 /completions 而不是 /chat/completions
        assertEquals("https://api.deepseek.com/beta/completions", request.url());
        assertNull(request.headers().get("Authorization"));
        assertTrue(request.body().contains("\"prompt\":\"public int add(int a, int b) {\\n\""));
        assertTrue(request.body().contains("\"suffix\":\"\\n}\""));
        assertTrue(request.body().contains("\"stream\":false"));
        // FIM 仅支持非思考模式，必须显式关闭
        assertTrue(request.body().contains("\"thinking\":{\"type\":\"disabled\"}"));
    }

    @Test
    public void shouldOmitBlankSuffixWithoutAuthorization() {
        AtomicReference<HttpRequest> captured = new AtomicReference<HttpRequest>();
        HttpTransport transport =
                request -> {
                    captured.set(request);
                    return new HttpResponse(200, SUCCESS_BODY);
                };

        FimModel model =
                OpenAiFimModel.builder()
                        .baseUrl("https://api.deepseek.com/beta")
                        .modelName("deepseek-flash")
                        .httpTransport(transport)
                        .build();

        // 文件末尾场景：suffix 为空
        model.complete(FimRequest.of("int x = ", ""));

        assertFalse(captured.get().body().contains("\"suffix\""));
        assertNull(captured.get().headers().get("Authorization"));
    }

    @Test
    public void shouldMergeRequestParametersOverModelDefaults() {
        AtomicReference<HttpRequest> captured = new AtomicReference<HttpRequest>();
        HttpTransport transport =
                request -> {
                    captured.set(request);
                    return new HttpResponse(200, SUCCESS_BODY);
                };

        FimModel model =
                OpenAiFimModel.builder()
                        .baseUrl("https://api.deepseek.com/beta")
                        .maxTokens(128)
                        .temperature(0.0d)
                        .httpTransport(transport)
                        .build();

        model.complete(
                FimRequest.builder()
                        .prefix("void run() {\n")
                        .suffix("\n}")
                        .parameters(
                                DefaultFimRequestParameters.builder()
                                        .maxTokens(32)
                                        .temperature(0.3d)
                                        .stopSequences(Arrays.asList("\n\n", "}\n"))
                                        .build())
                        .build());

        String body = captured.get().body();
        assertTrue("请求级 maxTokens 应覆盖模型级默认值", body.contains("\"max_tokens\":32"));
        assertTrue("请求级 temperature 应覆盖模型级默认值", body.contains("\"temperature\":0.3"));
        assertTrue(body.contains("\"stop\":[\"\\n\\n\",\"}\\n\"]"));
    }

    // ==================== 协议映射：响应侧 ====================

    @Test
    public void shouldParseTextUsageAndCacheHit() {
        HttpTransport transport = request -> new HttpResponse(200, SUCCESS_BODY);
        FimModel model =
                OpenAiFimModel.builder()
                        .baseUrl("https://api.deepseek.com/beta")
                        .httpTransport(transport)
                        .build();

        FimResponse response =
                model.complete(FimRequest.of("public int add(int a, int b) {\n", "\n}"));

        assertEquals("    return a + b;", response.completion());
        assertEquals(FimFinishReason.STOP, response.finishReason());
        assertEquals("deepseek-flash", response.model());
        assertNotNull(response.usage());
        assertEquals(12L, response.usage().inputTokens());
        assertEquals(4L, response.usage().outputTokens());
        assertEquals(16L, response.usage().totalTokens());
        // DeepSeek 用 prompt_cache_hit_tokens
        assertEquals(3L, response.usage().cachedInputTokens());
    }

    @Test
    public void shouldFallBackToOpenAiCachedTokensField() {
        String body =
                "{\"id\":\"x\",\"object\":\"text_completion\",\"model\":\"m\","
                        + "\"choices\":[{\"text\":\"ok\",\"index\":0,\"logprobs\":null,\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12,"
                        + "\"prompt_tokens_details\":{\"cached_tokens\":7}}}";

        FimResponse response = OpenAiFimWire.parseResponse(body, "m", 12L);

        assertEquals(7L, response.usage().cachedInputTokens());
        assertEquals(0.7d, response.usage().cacheHitRatio(), 0.0001d);
    }

    @Test
    public void shouldMapDeepSeekExtendedFinishReasons() {
        assertEquals(
                FimFinishReason.RESOURCE_LIMIT,
                OpenAiFimWire.mapFinishReason("insufficient_system_resource"));
        assertEquals(FimFinishReason.ABORTED, OpenAiFimWire.mapFinishReason("aborted"));
        assertEquals(FimFinishReason.LENGTH, OpenAiFimWire.mapFinishReason("length"));
        assertEquals(
                FimFinishReason.CONTENT_FILTER, OpenAiFimWire.mapFinishReason("content_filter"));
        assertEquals(FimFinishReason.OTHER, OpenAiFimWire.mapFinishReason("something_new"));
        assertNull(OpenAiFimWire.mapFinishReason(null));
    }

    @Test
    public void shouldThrowWhenChoicesMissing() {
        HttpTransport transport =
                request ->
                        new HttpResponse(
                                200,
                                "{\"id\":\"x\",\"object\":\"text_completion\",\"choices\":[]}");
        FimModel model =
                OpenAiFimModel.builder()
                        .baseUrl("https://api.deepseek.com/beta")
                        .httpTransport(transport)
                        .build();

        ModelException error =
                assertThrows(ModelException.class, () -> model.complete(FimRequest.of("a", "b")));
        assertTrue(error.getMessage().contains("no choices"));
    }

    @Test
    public void shouldSurfaceHttpStatusAndBodyOnFailure() {
        HttpTransport transport =
                request ->
                        new HttpResponse(
                                401, "{\"error\":{\"message\":\"upstream rejected request\"}}");
        FimModel model =
                OpenAiFimModel.builder()
                        .baseUrl("https://api.deepseek.com/beta")
                        .httpTransport(transport)
                        .build();

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
        FimModel model =
                OpenAiFimModel.builder()
                        .baseUrl("https://api.deepseek.com/beta")
                        .httpTransport(transport)
                        .build();

        ModelException error =
                assertThrows(ModelException.class, () -> model.complete(FimRequest.of("a", "b")));
        assertTrue(error.getMessage().contains("FIM request failed"));
    }

    // ==================== 调用语义 ====================

    @Test
    public void shouldRejectBlankModelName() {
        assertThrows(
                IllegalStateException.class,
                () -> OpenAiFimModel.builder().modelName("  ").build());
    }

    @Test
    public void shouldRejectEmptyRequest() {
        assertThrows(IllegalArgumentException.class, () -> FimRequest.builder().build());
    }

    @Test
    public void shouldDelegateCancelToHttpCall() {
        AtomicReference<HttpRequest> captured = new AtomicReference<HttpRequest>();
        HttpTransport transport =
                new HttpTransport() {
                    @Override
                    public HttpResponse execute(HttpRequest request) {
                        captured.set(request);
                        return new HttpResponse(200, SUCCESS_BODY);
                    }

                    @Override
                    public cloud.changlu.agentforge.model.http.HttpCall newCall(
                            final HttpRequest request) {
                        return new cloud.changlu.agentforge.model.http.HttpCall() {
                            @Override
                            public HttpResponse execute() {
                                return new HttpResponse(200, SUCCESS_BODY);
                            }

                            @Override
                            public boolean cancel() {
                                return true;
                            }

                            @Override
                            public boolean isCancelled() {
                                return true;
                            }
                        };
                    }
                };
        FimModel model =
                OpenAiFimModel.builder()
                        .baseUrl("https://api.deepseek.com/beta")
                        .httpTransport(transport)
                        .build();

        FimCall call = model.newCall(FimRequest.of("a", "b"));
        assertTrue("取消应委托给底层 HttpCall", call.cancel());
        assertTrue(call.isCancelled());
    }

    @Test
    public void shouldBuildWithDeepSeekDefaults() {
        FimModel model = OpenAiFimModel.builder().build();
        assertNotNull(model);
    }

    // ==================== 夹具 ====================

    private static final String SUCCESS_BODY =
            "{\"id\":\"60f16544-b4f2-4149-9326-79ccc6610d67\","
                    + "\"choices\":[{\"text\":\"    return a + b;\",\"index\":0,\"logprobs\":null,"
                    + "\"finish_reason\":\"stop\"}],"
                    + "\"created\":1793084841,\"model\":\"deepseek-flash\","
                    + "\"system_fingerprint\":\"e72d3c7f663d3e1383b00b3884cb4501\","
                    + "\"object\":\"text_completion\","
                    + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":4,\"total_tokens\":16,"
                    + "\"prompt_tokens_details\":{\"cached_tokens\":3},"
                    + "\"prompt_cache_hit_tokens\":3,\"prompt_cache_miss_tokens\":9}}";
}
