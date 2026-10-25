package cloud.changlu.agentforge.agent.memory.store;

import cloud.changlu.agentforge.model.chat.message.AiMessage;
import cloud.changlu.agentforge.model.chat.message.ChatMessage;
import cloud.changlu.agentforge.model.chat.message.ChatMessageType;
import cloud.changlu.agentforge.model.chat.message.SystemMessage;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionResultMessage;
import cloud.changlu.agentforge.model.chat.message.UserMessage;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChatMessageCodecTest {

    private final ChatMessageCodec codec = new ChatMessageCodec();

    @Test
    public void shouldRoundTripAllTextMessageTypes() {
        List<ChatMessage> originals =
                Arrays.<ChatMessage>asList(
                        SystemMessage.from("system"),
                        UserMessage.from("hello"),
                        AiMessage.from("world"),
                        ToolExecutionResultMessage.from("call-1", "echo", "ok"));

        List<ChatMessage> decoded = codec.decodeAll(codec.encodeAll(originals));

        assertEquals(originals, decoded);
    }

    @Test
    public void shouldRoundTripAiMessageWithThinkingAndToolCalls() {
        AiMessage original =
                AiMessage.builder()
                        .text("查询天气")
                        .thinking("需要调用工具")
                        .toolExecutionRequests(
                                Arrays.asList(
                                        ToolExecutionRequest.from(
                                                "call-1", "getWeather", "{\"city\":\"杭州\"}")))
                        .build();

        List<ChatMessage> decoded = codec.decodeAll(codec.encodeAll(Arrays.asList(original)));

        assertEquals(1, decoded.size());
        assertTrue(decoded.get(0) instanceof AiMessage);
        AiMessage restored = (AiMessage) decoded.get(0);
        assertEquals("查询天气", restored.text());
        assertEquals("需要调用工具", restored.thinking());
        assertTrue(restored.hasToolExecutionRequests());
        assertEquals("getWeather", restored.toolExecutionRequests().get(0).name());
        assertEquals("{\"city\":\"杭州\"}", restored.toolExecutionRequests().get(0).arguments());
    }

    @Test
    public void shouldRoundTripAttributes() {
        Map<String, Object> attributes = new LinkedHashMap<String, Object>();
        attributes.put("provider", "openai");
        AiMessage original = AiMessage.builder().text("hi").attributes(attributes).build();

        AiMessage restored =
                (AiMessage) codec.decodeAll(codec.encodeAll(Arrays.asList(original))).get(0);

        assertEquals(original, restored);
    }

    @Test
    public void shouldEncodeTypeField() {
        String json = codec.encodeAll(Arrays.asList(SystemMessage.from("s")));

        assertTrue(json.contains("\"type\":\"SYSTEM\""));
        assertTrue(json.contains(ChatMessageType.SYSTEM.name()));
    }

    @Test
    public void shouldReturnEmptyListForBlankJson() {
        assertTrue(codec.decodeAll(null).isEmpty());
        assertTrue(codec.decodeAll("  ").isEmpty());
    }

    @Test(expected = IllegalStateException.class)
    public void shouldRejectUnknownMessageType() {
        codec.decodeAll("[{\"type\":\"UNKNOWN\"}]");
    }
}
