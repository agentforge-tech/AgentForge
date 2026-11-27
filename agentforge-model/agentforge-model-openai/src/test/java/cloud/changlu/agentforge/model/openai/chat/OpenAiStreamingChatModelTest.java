package cloud.changlu.agentforge.model.openai.chat;

import cloud.changlu.agentforge.model.chat.message.SystemMessage;
import cloud.changlu.agentforge.model.chat.message.UserMessage;
import cloud.changlu.agentforge.model.chat.request.ChatRequest;
import cloud.changlu.agentforge.model.chat.request.DefaultChatRequestParameters;
import cloud.changlu.agentforge.model.chat.response.ChatResponse;
import cloud.changlu.agentforge.model.chat.response.FinishReason;
import cloud.changlu.agentforge.model.chat.response.StreamingChatResponseHandler;
import cloud.changlu.agentforge.model.exception.ModelException;
import cloud.changlu.agentforge.model.http.HttpRequest;
import cloud.changlu.agentforge.model.http.HttpResponse;
import cloud.changlu.agentforge.model.http.HttpTransport;
import cloud.changlu.agentforge.model.http.StreamingHttpResponseHandler;
import cloud.changlu.agentforge.model.internal.json.Json;
import cloud.changlu.agentforge.model.openai.OpenAiStreamingChatModel;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Tests OpenAI Chat Completions streaming behavior. The real-endpoint test is opt-in and is skipped
 * when no endpoint configuration is provided.
 *
 * @author changlu
 * @date 2026/09/13
 */
public class OpenAiStreamingChatModelTest {

    @Test
    public void shouldStreamPartialResponsesAndBuildCompleteResponse() {
        StreamingTransport transport =
                StreamingTransport.success(
                        "data: {\"id\":\"chatcmpl-1\",\"model\":\"gpt-test\",\"created\":123,\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"Hel\"},\"finish_reason\":null}]}",
                        "",
                        "data: {\"id\":\"chatcmpl-1\",\"model\":\"gpt-test\",\"choices\":[{\"delta\":{\"content\":\"lo\"},\"finish_reason\":null}]}",
                        "data: {\"id\":\"chatcmpl-1\",\"model\":\"gpt-test\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
                        "data: {\"id\":\"chatcmpl-1\",\"model\":\"gpt-test\",\"choices\":[],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2,\"total_tokens\":6,\"prompt_tokens_details\":{\"cached_tokens\":3}}}",
                        "data: [DONE]");

        OpenAiStreamingChatModel model =
                OpenAiStreamingChatModel.builder()
                        .modelName("gpt-test")
                        .temperature(0.2)
                        .customHeader("X-Test", "yes")
                        .httpTransport(transport)
                        .build();
        RecordingHandler handler = new RecordingHandler();

        model.chat(
                ChatRequest.builder()
                        .message(SystemMessage.from("Be concise"))
                        .message(UserMessage.from("Hi"))
                        .build(),
                handler);

        assertEquals(Arrays.asList("Hel", "lo"), handler.partials);
        assertNull(handler.error);
        assertNotNull(handler.completeResponse);
        assertEquals("Hello", handler.completeResponse.aiMessage().text());
        assertEquals(FinishReason.STOP, handler.completeResponse.finishReason());
        assertEquals(4L, handler.completeResponse.tokenUsage().inputTokens());
        assertEquals(2L, handler.completeResponse.tokenUsage().outputTokens());
        assertEquals(6L, handler.completeResponse.tokenUsage().totalTokens());
        assertEquals(3L, handler.completeResponse.tokenUsage().cachedTokens());
        assertEquals("chatcmpl-1", handler.completeResponse.metadata().get("id"));
        assertEquals("gpt-test", handler.completeResponse.metadata().get("model"));
        assertEquals(
                123L, ((Number) handler.completeResponse.metadata().get("created")).longValue());

        assertEquals("https://api.openai.com/v1/chat/completions", transport.lastRequest.url());
        assertFalse(transport.lastRequest.headers().containsKey("Authorization"));
        assertEquals("text/event-stream", transport.lastRequest.headers().get("Accept"));
        assertEquals("yes", transport.lastRequest.headers().get("X-Test"));

        Map<String, Object> payload = Json.parseObject(transport.lastRequest.body());
        assertEquals("gpt-test", payload.get("model"));
        assertEquals(Boolean.TRUE, payload.get("stream"));
        assertEquals(0.2d, ((Number) payload.get("temperature")).doubleValue(), 0.0d);
        Map<String, Object> streamOptions = Json.object(payload.get("stream_options"));
        assertEquals(Boolean.TRUE, streamOptions.get("include_usage"));

        List<Object> messages = Json.array(payload.get("messages"));
        assertEquals(2, messages.size());
        assertMessage(messages.get(0), "system", "Be concise");
        assertMessage(messages.get(1), "user", "Hi");
    }

    @Test
    public void shouldApplyRequestParametersOverModelDefaults() {
        StreamingTransport transport =
                StreamingTransport.success(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"length\"}]}",
                        "data: [DONE]");
        OpenAiStreamingChatModel model =
                OpenAiStreamingChatModel.builder()
                        .modelName("default-model")
                        .temperature(0.1)
                        .maxTokens(100)
                        .topP(0.8)
                        .customParameter("seed", 1)
                        .httpTransport(transport)
                        .build();

        DefaultChatRequestParameters overrides =
                DefaultChatRequestParameters.builder()
                        .modelName("request-model")
                        .temperature(0.7)
                        .maxTokens(20)
                        .topP(0.9)
                        .customParameter("seed", 2)
                        .build();
        RecordingHandler handler = new RecordingHandler();
        model.chat(
                ChatRequest.builder().message(UserMessage.from("hi")).parameters(overrides).build(),
                handler);

        Map<String, Object> payload = Json.parseObject(transport.lastRequest.body());
        assertEquals("request-model", payload.get("model"));
        assertEquals(0.7d, ((Number) payload.get("temperature")).doubleValue(), 0.0d);
        assertEquals(20L, ((Number) payload.get("max_tokens")).longValue());
        assertEquals(0.9d, ((Number) payload.get("top_p")).doubleValue(), 0.0d);
        assertEquals(2L, ((Number) payload.get("seed")).longValue());
        assertEquals("ok", handler.completeResponse.aiMessage().text());
        assertEquals(FinishReason.LENGTH, handler.completeResponse.finishReason());
    }

    @Test
    public void shouldForwardReasoningContentDeltas() {
        StreamingTransport transport =
                StreamingTransport.success(
                        "data: {\"id\":\"chatcmpl-r1\",\"model\":\"deepseek-test\",\"choices\":[{\"delta\":"
                                + "{\"reasoning_content\":\"先查一下\"},\"finish_reason\":null}]}",
                        "",
                        "data: {\"id\":\"chatcmpl-r1\",\"model\":\"deepseek-test\",\"choices\":[{\"delta\":"
                                + "{\"reasoning_content\":\"天气\"},\"finish_reason\":null}]}",
                        "data: {\"id\":\"chatcmpl-r1\",\"model\":\"deepseek-test\",\"choices\":[{\"delta\":"
                                + "{\"content\":\"Hangzhou 22C\"},\"finish_reason\":null}]}",
                        "data: {\"id\":\"chatcmpl-r1\",\"model\":\"deepseek-test\",\"choices\":[{\"delta\":{},"
                                + "\"finish_reason\":\"stop\"}]}",
                        "data: [DONE]");

        OpenAiStreamingChatModel model =
                OpenAiStreamingChatModel.builder()
                        .modelName("deepseek-test")
                        .httpTransport(transport)
                        .build();
        RecordingHandler handler = new RecordingHandler();

        model.chat("weather?", handler);

        assertEquals(Arrays.asList("先查一下", "天气"), handler.thinkings);
        assertEquals(Arrays.asList("Hangzhou 22C"), handler.partials);
        assertNull(handler.error);
        assertEquals("先查一下天气", handler.completeResponse.aiMessage().thinking());
        assertEquals("Hangzhou 22C", handler.completeResponse.aiMessage().text());
    }

    @Test
    public void shouldWorkWithLocalOpenAiCompatibleEndpoint() {
        StreamingTransport transport =
                StreamingTransport.success(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"local\"},\"finish_reason\":\"stop\"}]}"
                        // Intentionally no [DONE]: EOF should still produce the final response.
                        );
        OpenAiStreamingChatModel model =
                OpenAiStreamingChatModel.builder()
                        .baseUrl("http://localhost:11434/v1/")
                        .modelName("local-model")
                        .httpTransport(transport)
                        .build();
        RecordingHandler handler = new RecordingHandler();

        model.chat("hello", handler);

        assertEquals("http://localhost:11434/v1/chat/completions", transport.lastRequest.url());
        assertFalse(transport.lastRequest.headers().containsKey("Authorization"));
        assertEquals("local", handler.completeResponse.aiMessage().text());
    }

    @Test
    public void shouldExposeHttpFailureAsModelException() {
        StreamingTransport transport =
                new StreamingTransport(
                        429,
                        Collections.singletonList("{\"error\":{\"message\":\"rate limit\"}}"),
                        null);
        OpenAiStreamingChatModel model =
                OpenAiStreamingChatModel.builder()
                        .modelName("gpt-test")
                        .httpTransport(transport)
                        .build();
        RecordingHandler handler = new RecordingHandler();

        model.chat("hello", handler);

        assertNull(handler.completeResponse);
        assertTrue(handler.error instanceof ModelException);
        ModelException error = (ModelException) handler.error;
        assertEquals(Integer.valueOf(429), error.statusCode());
        assertTrue(error.responseBody().contains("rate limit"));
    }

    @Test
    public void shouldReportMalformedSseChunk() {
        StreamingTransport transport = StreamingTransport.success("data: {not-json}");
        OpenAiStreamingChatModel model =
                OpenAiStreamingChatModel.builder()
                        .modelName("gpt-test")
                        .httpTransport(transport)
                        .build();
        RecordingHandler handler = new RecordingHandler();

        model.chat("hello", handler);

        assertNull(handler.completeResponse);
        assertTrue(handler.error instanceof ModelException);
        assertTrue(handler.error.getMessage().contains("parse"));
    }

    @Test
    public void shouldWrapStreamingTransportFailure() {
        StreamingTransport transport =
                new StreamingTransport(
                        200, Collections.<String>emptyList(), new IOException("network down"));
        OpenAiStreamingChatModel model =
                OpenAiStreamingChatModel.builder()
                        .modelName("gpt-test")
                        .httpTransport(transport)
                        .build();
        RecordingHandler handler = new RecordingHandler();

        model.chat("hello", handler);

        assertTrue(handler.error instanceof ModelException);
        assertNotNull(handler.error.getCause());
        assertEquals("network down", handler.error.getCause().getMessage());
    }

    @Test
    public void shouldValidateModelAndHandler() {
        final StreamingTransport transport = StreamingTransport.success("data: [DONE]");
        final OpenAiStreamingChatModel missingModel =
                OpenAiStreamingChatModel.builder().httpTransport(transport).build();
        final ChatRequest request = ChatRequest.builder().message(UserMessage.from("hi")).build();
        final RecordingHandler handler = new RecordingHandler();

        assertThrows(IllegalStateException.class, () -> missingModel.chat(request, handler));

        final OpenAiStreamingChatModel model =
                OpenAiStreamingChatModel.builder()
                        .modelName("gpt-test")
                        .httpTransport(transport)
                        .build();
        assertThrows(IllegalArgumentException.class, () -> model.chat((ChatRequest) null, handler));
        assertThrows(IllegalArgumentException.class, () -> model.chat(request, null));
    }

    @SuppressWarnings("unchecked")
    private static void assertMessage(Object value, String role, String content) {
        Map<String, Object> message = (Map<String, Object>) value;
        assertEquals(role, message.get("role"));
        assertEquals(content, message.get("content"));
    }

    private static final class RecordingHandler implements StreamingChatResponseHandler {
        private final List<String> partials = new ArrayList<String>();
        private final List<String> thinkings = new ArrayList<String>();
        private final CountDownLatch completion = new CountDownLatch(1);
        private volatile ChatResponse completeResponse;
        private volatile Throwable error;

        @Override
        public void onPartialResponse(String partialResponse) {
            partials.add(partialResponse);
        }

        @Override
        public void onPartialThinking(String partialThinking) {
            thinkings.add(partialThinking);
        }

        @Override
        public void onCompleteResponse(ChatResponse completeResponse) {
            this.completeResponse = completeResponse;
            completion.countDown();
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
            completion.countDown();
        }

        private boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return completion.await(timeout, unit);
        }
    }

    private static final class StreamingTransport implements HttpTransport {
        private final int statusCode;
        private final List<String> lines;
        private final Throwable failure;
        private HttpRequest lastRequest;

        private StreamingTransport(int statusCode, List<String> lines, Throwable failure) {
            this.statusCode = statusCode;
            this.lines = lines;
            this.failure = failure;
        }

        private static StreamingTransport success(String... lines) {
            return new StreamingTransport(200, Arrays.asList(lines), null);
        }

        @Override
        public HttpResponse execute(HttpRequest request) {
            throw new UnsupportedOperationException("not used by streaming tests");
        }

        @Override
        public void executeStreaming(HttpRequest request, StreamingHttpResponseHandler handler) {
            this.lastRequest = request;
            if (failure != null) {
                handler.onError(failure);
                return;
            }
            handler.onOpen(statusCode, Collections.<String, List<String>>emptyMap());
            for (String line : lines) {
                handler.onLine(line);
            }
            handler.onComplete();
        }
    }
}
