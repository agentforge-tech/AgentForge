package cloud.changlu.agentforge.model.openai.chat;

import cloud.changlu.agentforge.model.chat.message.AiMessage;
import cloud.changlu.agentforge.model.chat.message.SystemMessage;
import cloud.changlu.agentforge.model.chat.message.UserMessage;
import cloud.changlu.agentforge.model.chat.request.ChatRequest;
import cloud.changlu.agentforge.model.chat.request.DefaultChatRequestParameters;
import cloud.changlu.agentforge.model.chat.response.ChatResponse;
import cloud.changlu.agentforge.model.chat.response.FinishReason;
import cloud.changlu.agentforge.model.exception.ModelException;
import cloud.changlu.agentforge.model.http.HttpRequest;
import cloud.changlu.agentforge.model.http.HttpResponse;
import cloud.changlu.agentforge.model.http.HttpTransport;
import cloud.changlu.agentforge.model.internal.json.Json;
import cloud.changlu.agentforge.model.openai.OpenAiChatModel;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class OpenAiChatModelTest {

    @Test
    public void shouldMapRequestAndNormalizeResponse() {
        CapturingTransport transport =
                new CapturingTransport(
                        new HttpResponse(
                                200,
                                "{\"id\":\"chatcmpl-1\",\"model\":\"gpt-test\",\"created\":123,"
                                        + "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hello!\"},\"finish_reason\":\"stop\"}],"
                                        + "\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2,\"total_tokens\":6,\"prompt_tokens_details\":{\"cached_tokens\":3}}}"));

        OpenAiChatModel model =
                OpenAiChatModel.builder()
                        .baseUrl("https://gateway.example/v1/")
                        .modelName("gpt-default")
                        .temperature(0.2)
                        .maxTokens(256)
                        .customHeader("X-Project", "agentforge")
                        .httpTransport(transport)
                        .connectTimeoutMillis(1234)
                        .readTimeoutMillis(5678)
                        .build();

        DefaultChatRequestParameters requestParameters =
                DefaultChatRequestParameters.builder()
                        .modelName("gpt-request")
                        .temperature(0.7)
                        .topP(0.9)
                        .stopSequences(Arrays.asList("END"))
                        .customParameter("seed", 42)
                        .build();

        ChatResponse response =
                model.chat(
                        ChatRequest.builder()
                                .message(SystemMessage.from("Be concise"))
                                .message(UserMessage.from("Hi"))
                                .message(AiMessage.from("Earlier answer"))
                                .parameters(requestParameters)
                                .build());

        HttpRequest request = transport.lastRequest;
        assertNotNull(request);
        assertEquals("https://gateway.example/v1/chat/completions", request.url());
        assertEquals("POST", request.method());
        assertFalse(request.headers().containsKey("Authorization"));
        assertEquals("agentforge", request.headers().get("X-Project"));
        assertEquals("application/json", request.headers().get("Content-Type"));
        assertEquals(1234, request.connectTimeoutMillis());
        assertEquals(5678, request.readTimeoutMillis());

        Map<String, Object> payload = Json.parseObject(request.body());
        assertEquals("gpt-request", payload.get("model"));
        assertEquals(0.7d, ((Number) payload.get("temperature")).doubleValue(), 0.00001d);
        assertEquals(256L, ((Number) payload.get("max_tokens")).longValue());
        assertEquals(0.9d, ((Number) payload.get("top_p")).doubleValue(), 0.00001d);
        assertEquals(Arrays.asList("END"), payload.get("stop"));
        assertEquals(42L, ((Number) payload.get("seed")).longValue());

        List<Object> messages = Json.array(payload.get("messages"));
        assertEquals(3, messages.size());
        assertMessage(messages.get(0), "system", "Be concise");
        assertMessage(messages.get(1), "user", "Hi");
        assertMessage(messages.get(2), "assistant", "Earlier answer");

        assertEquals("Hello!", response.aiMessage().text());
        assertEquals(FinishReason.STOP, response.finishReason());
        assertEquals(4L, response.tokenUsage().inputTokens());
        assertEquals(2L, response.tokenUsage().outputTokens());
        assertEquals(6L, response.tokenUsage().totalTokens());
        assertEquals(3L, response.tokenUsage().cachedTokens());
        assertEquals("chatcmpl-1", response.metadata().get("id"));
        assertEquals("gpt-test", response.metadata().get("model"));
    }

    @Test
    public void shouldWorkWithLocalOpenAiCompatibleEndpoint() {
        CapturingTransport transport =
                new CapturingTransport(
                        new HttpResponse(
                                200,
                                "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"length\"}]}"));

        OpenAiChatModel model =
                OpenAiChatModel.builder()
                        .baseUrl("http://localhost:11434/v1")
                        .modelName("local-model")
                        .httpTransport(transport)
                        .build();

        ChatResponse response =
                model.chat(ChatRequest.builder().message(UserMessage.from("hi")).build());

        assertFalse(transport.lastRequest.headers().containsKey("Authorization"));
        assertEquals("ok", response.aiMessage().text());
        assertEquals(FinishReason.LENGTH, response.finishReason());
        assertNull(response.tokenUsage());
    }

    @Test
    public void shouldMapToolCallFinishReasonAndArrayContent() {
        CapturingTransport transport =
                new CapturingTransport(
                        new HttpResponse(
                                200,
                                "{\"choices\":[{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"A\"},{\"type\":\"text\",\"text\":\"B\"}]},"
                                        + "\"finish_reason\":\"tool_calls\"}]}"));
        OpenAiChatModel model =
                OpenAiChatModel.builder().modelName("gpt-test").httpTransport(transport).build();

        ChatResponse response =
                model.chat(ChatRequest.builder().message(UserMessage.from("hi")).build());

        assertEquals("AB", response.aiMessage().text());
        assertEquals(FinishReason.TOOL_EXECUTION, response.finishReason());
    }

    @Test
    public void shouldExposeHttpFailureAsModelException() {
        CapturingTransport transport =
                new CapturingTransport(new HttpResponse(429, "{\"error\":\"rate_limit\"}"));
        OpenAiChatModel model =
                OpenAiChatModel.builder().modelName("gpt-test").httpTransport(transport).build();

        ModelException error =
                assertThrows(
                        ModelException.class,
                        () ->
                                model.chat(
                                        ChatRequest.builder()
                                                .message(UserMessage.from("hi"))
                                                .build()));

        assertEquals(Integer.valueOf(429), error.statusCode());
        assertTrue(error.responseBody().contains("rate_limit"));
    }

    @Test
    public void shouldWrapTransportIOException() {
        HttpTransport failing =
                new HttpTransport() {
                    @Override
                    public HttpResponse execute(HttpRequest request) throws IOException {
                        throw new IOException("network down");
                    }
                };
        OpenAiChatModel model =
                OpenAiChatModel.builder().modelName("gpt-test").httpTransport(failing).build();

        ModelException error =
                assertThrows(
                        ModelException.class,
                        () ->
                                model.chat(
                                        ChatRequest.builder()
                                                .message(UserMessage.from("hi"))
                                                .build()));
        assertNotNull(error.getCause());
        assertEquals("network down", error.getCause().getMessage());
    }

    @Test
    public void shouldValidateModelNameAndResponseShape() {
        final ChatRequest request = ChatRequest.builder().message(UserMessage.from("hi")).build();
        OpenAiChatModel missingModel =
                OpenAiChatModel.builder()
                        .httpTransport(new CapturingTransport(new HttpResponse(200, "{}")))
                        .build();
        assertThrows(IllegalStateException.class, () -> missingModel.chat(request));

        OpenAiChatModel badResponse =
                OpenAiChatModel.builder()
                        .modelName("gpt-test")
                        .httpTransport(
                                new CapturingTransport(new HttpResponse(200, "{\"choices\":[]}")))
                        .build();
        assertThrows(ModelException.class, () -> badResponse.chat(request));
    }

    @SuppressWarnings("unchecked")
    private static void assertMessage(Object value, String role, String content) {
        Map<String, Object> message = (Map<String, Object>) value;
        assertEquals(role, message.get("role"));
        assertEquals(content, message.get("content"));
    }

    private static final class CapturingTransport implements HttpTransport {
        private final HttpResponse response;
        private HttpRequest lastRequest;

        private CapturingTransport(HttpResponse response) {
            this.response = response;
        }

        @Override
        public HttpResponse execute(HttpRequest request) {
            this.lastRequest = request;
            return response;
        }
    }
}
