package cloud.changlu.agentforge.agent.memory.window;

import cloud.changlu.agentforge.agent.memory.ChatMemory;
import cloud.changlu.agentforge.agent.memory.ChatMemoryProvider;
import cloud.changlu.agentforge.agent.memory.store.ChatMemoryStore;
import cloud.changlu.agentforge.agent.memory.store.InMemoryChatMemoryStore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 消息窗口记忆提供者，同一 memoryId 复用同一个记忆实例，所有实例共享同一个 {@link ChatMemoryStore}。
 *
 * <p>默认使用进程内 {@link InMemoryChatMemoryStore}；替换为 MySQL 等实现后，记忆状态即从进程内变为外部持久化。
 */
public final class MessageWindowChatMemoryProvider implements ChatMemoryProvider {

    private final int maxMessages;
    private final ChatMemoryStore store;
    private final Map<Object, ChatMemory> memories = new ConcurrentHashMap<Object, ChatMemory>();

    public MessageWindowChatMemoryProvider(int maxMessages) {
        this(maxMessages, new InMemoryChatMemoryStore());
    }

    public MessageWindowChatMemoryProvider(int maxMessages, ChatMemoryStore store) {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages must be greater than zero");
        }
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        this.maxMessages = maxMessages;
        this.store = store;
    }

    @Override
    public ChatMemory get(Object memoryId) {
        ChatMemory memory = memories.get(memoryId);
        if (memory == null) {
            memory =
                    MessageWindowChatMemory.builder()
                            .id(memoryId)
                            .maxMessages(maxMessages)
                            .chatMemoryStore(store)
                            .build();
            ChatMemory previous = memories.putIfAbsent(memoryId, memory);
            if (previous != null) {
                memory = previous;
            }
        }
        return memory;
    }
}
