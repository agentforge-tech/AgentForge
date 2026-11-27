package cloud.changlu.agentforge.model.openai.fim;

import cloud.changlu.agentforge.model.cancel.CancellationHandle;
import cloud.changlu.agentforge.model.fim.StreamingFimModel;
import cloud.changlu.agentforge.model.fim.request.DefaultFimRequestParameters;
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
import cloud.changlu.agentforge.model.openai.OpenAiStreamingFimModel;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link OpenAiStreamingFimModel} 单元测试。
 *
 * @author changlu
 * @date 2026/11/25
 */
public class OpenAiStreamingFimModelTest {

    // ==================== 流式聚合语义 ====================

    @Test
    public void shouldAggregateChunksAndSendStreamOptions() {
        StreamingTransport transport =
                StreamingTransport.success(
                        "data: {\"id\":\"1\",\"model\":\"deepseek-flash\",\"object\":\"text_completion\","
                                + "\"choices\":[{\"text\":\"\",\"index\":0,\"logprobs\":null,\"finish_reason\":null}]}",
                        "",
                        "data: {\"id\":\"1\",\"model\":\"deepseek-flash\",\"choices\":[{\"text\":\"    return\",\"index\":0,\"logprobs\":null,\"finish_reason\":null}]}",
                        "data: {\"id\":\"1\",\"model\":\"deepseek-flash\",\"choices\":[{\"text\":\" a + b;\",\"index\":0,\"logprobs\":null,\"finish_reason\":null}]}",
                        // 最后一个内容块：text 为空、finish_reason 非空、带 usage
                        "data: {\"id\":\"1\",\"model\":\"deepseek-flash\",\"choices\":[{\"text\":\"\",\"index\":0,\"logprobs\":null,\"finish_reason\":\"stop\"}],"
                                + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":4,\"total_tokens\":16,\"prompt_cache_hit_tokens\":5}}",
                        "data: [DONE]");

        StreamingFimModel model =
                OpenAiStreamingFimModel.builder()
                        .baseUrl("https://api.deepseek.com/beta")
                        .modelName("deepseek-flash")
                        .httpTransport(transport)
                        .build();

        RecordingHandler handler = new RecordingHandler();
        model.complete(FimRequest.of("public int add(int a, int b) {\n", "\n}"), handler);

        assertNull(handler.error);
        assertNotNull(handler.completeResponse);
        assertEquals("    return a + b;", handler.completeResponse.completion());
        assertEquals(FimFinishReason.STOP, handler.completeResponse.finishReason());
        assertEquals(2, handler.chunkCount.get());
        assertTrue("首个非空片段应单独回调", handler.firstChunkFired);

        FimUsage usage = handler.completeResponse.usage();
        assertNotNull("usage 必须从最后一个内容块中取到", usage);
        assertEquals(12L, usage.inputTokens());
        assertEquals(4L, usage.outputTokens());
        assertEquals(5L, usage.cachedInputTokens());

        // 请求体断言：必须带 stream=true 与 stream_options.include_usage
        assertTrue(transport.lastRequest.body().contains("\"stream\":true"));
        assertTrue(transport.lastRequest.body().contains("\"include_usage\":true"));
        assertTrue(transport.lastRequest.body().contains("\"thinking\":{\"type\":\"disabled\"}"));
    }

    @Test
    public void shouldIgnoreMalformedChunkAndKeepStreaming() {
        StreamingTransport transport =
                StreamingTransport.success(
                        "data: {this-is-not-json}",
                        "data: {\"id\":\"1\",\"choices\":[{\"text\":\"ok\",\"index\":0,\"logprobs\":null,\"finish_reason\":null}]}",
                        "data: {\"id\":\"1\",\"choices\":[{\"text\":\"\",\"index\":0,\"logprobs\":null,\"finish_reason\":\"stop\"}]}",
                        "data: [DONE]");

        StreamingFimModel model =
                OpenAiStreamingFimModel.builder()
                        .baseUrl("https://api.deepseek.com/beta")
                        .httpTransport(transport)
                        .build();

        RecordingHandler handler = new RecordingHandler();
        model.complete(FimRequest.of("a", "b"), handler);

        assertNull(handler.error);
        assertEquals("ok", handler.completeResponse.completion());
    }

    @Test
    public void shouldReportHttpErrorThroughHandler() {
        StreamingTransport transport = new StreamingTransport(401, Collections.<String>emptyList());

        StreamingFimModel model =
                OpenAiStreamingFimModel.builder()
                        .baseUrl("https://api.deepseek.com/beta")
                        .httpTransport(transport)
                        .build();

        RecordingHandler handler = new RecordingHandler();
        model.complete(FimRequest.of("a", "b"), handler);

        assertNotNull(handler.error);
        assertTrue(handler.error.getMessage().contains("401"));
        // 错误后不应再回调 onComplete
        assertNull(handler.completeResponse);
    }

    @Test
    public void shouldOnlyCompleteOnce() {
        StreamingTransport transport =
                StreamingTransport.success(
                        "data: {\"id\":\"1\",\"choices\":[{\"text\":\"x\",\"index\":0,\"logprobs\":null,\"finish_reason\":\"stop\"}]}",
                        "data: [DONE]");

        StreamingFimModel model =
                OpenAiStreamingFimModel.builder()
                        .baseUrl("https://api.deepseek.com/beta")
                        .httpTransport(transport)
                        .build();

        RecordingHandler handler = new RecordingHandler();
        model.complete(FimRequest.of("a", "b"), handler);
        transport.emitCompleteAgain();

        assertEquals(1, handler.completeCount.get());
    }

    @Test
    public void shouldPassStopAndMaxTokensFromParameters() {
        StreamingTransport transport =
                StreamingTransport.success(
                        "data: {\"id\":\"1\",\"choices\":[{\"text\":\"x\",\"index\":0,\"logprobs\":null,\"finish_reason\":\"stop\"}]}",
                        "data: [DONE]");

        StreamingFimModel model =
                OpenAiStreamingFimModel.builder()
                        .baseUrl("https://api.deepseek.com/beta")
                        .maxTokens(128)
                        .httpTransport(transport)
                        .build();

        RecordingHandler handler = new RecordingHandler();
        model.complete(
                FimRequest.builder()
                        .prefix("a")
                        .suffix("b")
                        .parameters(
                                DefaultFimRequestParameters.builder()
                                        .maxTokens(24)
                                        .stopSequences(Collections.singletonList("\n\n"))
                                        .build())
                        .build(),
                handler);

        String body = transport.lastRequest.body();
        assertTrue(body.contains("\"max_tokens\":24"));
        assertTrue(body.contains("\"stop\":[\"\\n\\n\"]"));
    }

    /**
     * 取消语义：{@code completeCancellable} 必须把句柄透出，且取消后不应再产生完成回调。
     *
     * <p>真实链路里"取消后不再回调"由 {@code JdkHttpTransport} 保证（它会在取消后提前 return）， Provider
     * 侧的责任是<b>原样透出传输层句柄</b>，让上层能真正掐断上行请求。这里断言的就是这条契约。
     */
    @Test
    public void shouldExposeCancellableHandleAndStopCompleting() {
        CancellableTransport transport = new CancellableTransport();

        StreamingFimModel model =
                OpenAiStreamingFimModel.builder()
                        .baseUrl("https://api.deepseek.com/beta")
                        .httpTransport(transport)
                        .build();

        RecordingHandler handler = new RecordingHandler();
        CancellationHandle handle = model.completeCancellable(FimRequest.of("a", "b"), handler);

        assertTrue("取消应生效", handle.cancel());
        assertTrue("句柄必须反映已取消", handle.isCancelled());
        // 取消后传输层不再回调完成，Provider 也就不应产生完成回调
        assertEquals(0, handler.completeCount.get());
        assertNull(handler.completeResponse);
    }

    // ==================== 夹具 ====================

    /** 记录回调，便于断言聚合结果。 */
    private static final class RecordingHandler implements StreamingFimResponseHandler {
        private final AtomicInteger chunkCount = new AtomicInteger();
        private final AtomicInteger completeCount = new AtomicInteger();
        private final List<String> chunks = new ArrayList<String>();
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile boolean firstChunkFired;
        private volatile FimResponse completeResponse;
        private volatile Throwable error;

        @Override
        public void onFirstChunk(FimChunk chunk) {
            firstChunkFired = true;
        }

        @Override
        public void onPartialCompletion(FimChunk chunk) {
            chunks.add(chunk.delta());
            chunkCount.incrementAndGet();
        }

        @Override
        public void onComplete(FimResponse completeResponse) {
            this.completeResponse = completeResponse;
            completeCount.incrementAndGet();
            latch.countDown();
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
            latch.countDown();
        }

        boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }
    }

    /** 假流式 Transport：按行回放，可重复触发 onComplete 以验证幂等。 */
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

        void emitCompleteAgain() {
            if (activeHandler != null) {
                activeHandler.onComplete();
            }
        }
    }

    /** 可取消的假流式 Transport：立即返回一个可取消句柄，且不主动回调完成。 */
    private static final class CancellableTransport implements HttpTransport {
        @Override
        public HttpResponse execute(HttpRequest request) {
            throw new UnsupportedOperationException("not used by streaming tests");
        }

        @Override
        public CancellationHandle executeStreamingCancellable(
                HttpRequest request, StreamingHttpResponseHandler handler) {
            return new CancellationHandle() {
                private volatile boolean cancelled;

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

        @Override
        public void executeStreaming(HttpRequest request, StreamingHttpResponseHandler handler) {
            executeStreamingCancellable(request, handler);
        }
    }
}
