package cloud.changlu.agentforge.agent.memory;

import cloud.changlu.agentforge.model.chat.message.AiMessage;
import cloud.changlu.agentforge.model.chat.message.ChatMessage;
import cloud.changlu.agentforge.model.chat.message.SystemMessage;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionResultMessage;
import cloud.changlu.agentforge.model.chat.message.UserMessage;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class MessageWindowChatMemoryTest {

    @Test
    public void shouldBatchAddAndReplaceSystemMessage() {
        MessageWindowChatMemory memory =
                MessageWindowChatMemory.builder().id("s1").maxMessages(4).build();

        memory.add(
                Arrays.<ChatMessage>asList(
                        SystemMessage.from("old"),
                        UserMessage.from("q1"),
                        SystemMessage.from("new"),
                        AiMessage.from("a1")));

        List<ChatMessage> messages = memory.messages();
        assertEquals(3, messages.size());
        assertEquals("q1", messages.get(0).text());
        assertEquals("new", messages.get(1).text());
        assertEquals("a1", messages.get(2).text());
    }

    @Test
    public void shouldEvictToolResultsTogetherWithAiToolCall() {
        MessageWindowChatMemory memory = MessageWindowChatMemory.withMaxMessages(3);
        ToolExecutionRequest request = ToolExecutionRequest.from("call-1", "echo", "{}");

        memory.add(
                SystemMessage.from("system"),
                AiMessage.from(request),
                new ToolExecutionResultMessage("call-1", "echo", "ok"),
                UserMessage.from("next"));

        List<ChatMessage> messages = memory.messages();
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).text());
        assertEquals("next", messages.get(1).text());
    }

    @Test
    public void shouldReuseMessageWindowMemoryPerSession() {
        ChatMemoryProvider provider = new MessageWindowChatMemoryProvider(10);
        ChatMemory first = provider.get("s1");

        assertTrue(first instanceof MessageWindowChatMemory);
        assertSame(first, provider.get("s1"));
        assertTrue(provider.get("s2") != first);
    }
}
