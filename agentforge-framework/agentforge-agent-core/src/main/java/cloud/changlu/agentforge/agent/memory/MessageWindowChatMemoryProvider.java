package cloud.changlu.agentforge.agent.memory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 进程内消息窗口记忆提供者，同一 memoryId 复用同一个记忆实例。 */
public final class MessageWindowChatMemoryProvider implements ChatMemoryProvider {

    private final int maxMessages;
    private final Map<Object, ChatMemory> memories = new ConcurrentHashMap<Object, ChatMemory>();

    public MessageWindowChatMemoryProvider(int maxMessages) {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages must be greater than zero");
        }
        this.maxMessages = maxMessages;
    }

    @Override
    public ChatMemory get(Object memoryId) {
        ChatMemory memory = memories.get(memoryId);
        if (memory == null) {
            memory =
                    MessageWindowChatMemory.builder().id(memoryId).maxMessages(maxMessages).build();
            ChatMemory previous = memories.putIfAbsent(memoryId, memory);
            if (previous != null) {
                memory = previous;
            }
        }
        return memory;
    }
}
