package cloud.changlu.agentforge.model.dashscope.fim;

import cloud.changlu.agentforge.model.fim.StreamingFimModel;
import cloud.changlu.agentforge.model.fim.request.FimRequest;
import cloud.changlu.agentforge.model.fim.response.FimChunk;
import cloud.changlu.agentforge.model.fim.response.FimFinishReason;
import cloud.changlu.agentforge.model.fim.response.FimResponse;
import cloud.changlu.agentforge.model.fim.response.FimUsage;
import cloud.changlu.agentforge.model.fim.response.StreamingFimResponseHandler;
import cloud.changlu.agentforge.model.http.HttpRequest;
import cloud.changlu.agentforge.model.http.HttpResponse;
import cloud.changlu.agentforge.model.http.HttpTransport;
import cloud.changlu.agentforge.model.http.StreamingHttpResponseHandler;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link DashScopeStreamingFimModel} 测试：验证 {@code delta.content} 聚合与 usage 末块。
 *
 * @author changlu
 * @date 2026/11/25
 */
public class DashScopeStreamingFimModelTest {

    @Test
    public void shouldAggregateDeltaContentAndUsage() {
        StreamingTransport transport =
                StreamingTransport.success(
                        "data: {\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"model\":\"qwen3-coder-flash\","
                                + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":null}],\"usage\":null}",
                        "",
                        "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"    return\"},\"finish_reason\":null}],\"usage\":null}",
                        "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\" a + b;\"},\"finish_reason\":null}],\"usage\":null}",
                        "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                                + "\"usage\":{\"prompt_tokens\":48,\"completion_tokens\":19,\"total_tokens\":67,"
                                + "\"prompt_tokens_details\":{\"cached_tokens\":12}}}",
                        "data: [DONE]");

        StreamingFimModel model =
                DashScopeStreamingFimModel.builder()
                        .modelName("qwen3-coder-flash")
                        .httpTransport(transport)
                        .build();

        RecordingHandler handler = new RecordingHandler();
        model.complete(FimRequest.of("public int add(int a, int b) {\n", "\n}"), handler);

        assertNull(handler.error);
        assertNotNull(handler.response);
        assertEquals("    return a + b;", handler.response.completion());
        assertEquals(FimFinishReason.STOP, handler.response.finishReason());
        assertEquals(2, handler.chunkCount.get());
        assertTrue("首个非空片段应单独回调", handler.firstChunkFired);

        FimUsage usage = handler.response.usage();
        assertNotNull("usage 必须从末块取到", usage);
        assertEquals(48L, usage.inputTokens());
        assertEquals(19L, usage.outputTokens());
        assertEquals(12L, usage.cachedInputTokens());

        assertTrue(transport.lastRequest.body().contains("\"stream\":true"));
        assertTrue(transport.lastRequest.body().contains("\"include_usage\":true"));
        assertTrue(transport.lastRequest.body().contains("\"partial\":true"));
    }

    @Test
    public void shouldIgnoreMalformedChunk() {
        StreamingTransport transport =
                StreamingTransport.success(
                        "data: {not-json}",
                        "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":null}]}",
                        "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}",
                        "data: [DONE]");

        StreamingFimModel model =
                DashScopeStreamingFimModel.builder().httpTransport(transport).build();

        RecordingHandler handler = new RecordingHandler();
        model.complete(FimRequest.of("a", "b"), handler);

        assertNull(handler.error);
        assertEquals("ok", handler.response.completion());
    }

    @Test
    public void shouldReportHttpError() {
        StreamingTransport transport = new StreamingTransport(401, Collections.<String>emptyList());

        StreamingFimModel model =
                DashScopeStreamingFimModel.builder().httpTransport(transport).build();

        RecordingHandler handler = new RecordingHandler();
        model.complete(FimRequest.of("a", "b"), handler);

        assertNotNull(handler.error);
        assertTrue(handler.error.getMessage().contains("401"));
        assertNull(handler.response);
    }

    @Test
    public void shouldCompleteOnlyOnce() {
        StreamingTransport transport =
                StreamingTransport.success(
                        "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"x\"},\"finish_reason\":\"stop\"}]}",
                        "data: [DONE]");

        StreamingFimModel model =
                DashScopeStreamingFimModel.builder().httpTransport(transport).build();

        RecordingHandler handler = new RecordingHandler();
        model.complete(FimRequest.of("a", "b"), handler);
        transport.emitCompleteAgain();

        assertEquals(1, handler.completeCount.get());
    }

    @Test
    public void shouldUseBailianEndpointAndModel() {
        StreamingTransport transport =
                StreamingTransport.success(
                        "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"x\"},\"finish_reason\":\"stop\"}]}",
                        "data: [DONE]");

        StreamingFimModel model =
                DashScopeStreamingFimModel.builder().httpTransport(transport).build();
        model.complete(FimRequest.of("a", "b"), new RecordingHandler());

        assertTrue(transport.lastRequest.url().endsWith("/compatible-mode/v1/chat/completions"));
        assertTrue(transport.lastRequest.body().contains("qwen3-coder-flash"));
    }

    @Test
    public void shouldExposeBailianShortcut() {
        assertNotNull(DashScopeStreamingFimModel.builder().build());
    }

    // ==================== 夹具 ====================

    private static final class RecordingHandler implements StreamingFimResponseHandler {
        private final AtomicInteger chunkCount = new AtomicInteger();
        private final AtomicInteger completeCount = new AtomicInteger();
        private volatile boolean firstChunkFired;
        private volatile FimResponse response;
        private volatile Throwable error;

        @Override
        public void onFirstChunk(FimChunk chunk) {
            firstChunkFired = true;
        }

        @Override
        public void onPartialCompletion(FimChunk chunk) {
            chunkCount.incrementAndGet();
        }

        @Override
        public void onComplete(FimResponse completeResponse) {
            this.response = completeResponse;
            completeCount.incrementAndGet();
        }

        @Override
        public void onError(Throwable throwable) {
            this.error = throwable;
        }
    }

    private static final class StreamingTransport implements HttpTransport {
        private final int statusCode;
        private final List<String> lines;
        private HttpRequest lastRequest;
        private StreamingHttpResponseHandler activeHandler;

        private StreamingTransport(int statusCode, List<String> lines) {
            this.statusCode = statusCode;
            this.lines = lines;
        }

        private static StreamingTransport success(String... lines) {
            return new StreamingTransport(200, Arrays.asList(lines));
        }

        @Override
        public HttpResponse execute(HttpRequest request) {
            throw new UnsupportedOperationException("not used by streaming tests");
        }

        @Override
        public void executeStreaming(HttpRequest request, StreamingHttpResponseHandler handler) {
            this.lastRequest = request;
            this.activeHandler = handler;
            handler.onOpen(statusCode, Collections.<String, List<String>>emptyMap());
            for (String line : lines) {
                handler.onLine(line);
            }
            handler.onComplete();
        }

        private void emitCompleteAgain() {
            if (activeHandler != null) {
                activeHandler.onComplete();
            }
        }
    }
}
