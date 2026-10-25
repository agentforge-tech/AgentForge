package cloud.changlu.agentforge.agent.memory.window;

import cloud.changlu.agentforge.agent.memory.ChatMemory;
import cloud.changlu.agentforge.agent.memory.store.ChatMemoryStore;
import cloud.changlu.agentforge.agent.memory.store.InMemoryChatMemoryStore;
import cloud.changlu.agentforge.model.chat.message.AiMessage;
import cloud.changlu.agentforge.model.chat.message.ChatMessage;
import cloud.changlu.agentforge.model.chat.message.ChatMessageType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

/**
 * 支持批量写入的消息窗口记忆。自身不持有任何消息状态，全部读写都委托给 {@link ChatMemoryStore}，只负责窗口裁剪语义：
 *
 * <ul>
 *   <li>系统消息始终位于窗口中，重复的系统消息会被替换而不是追加
 *   <li>淘汰带工具调用的 AI 消息时，会同步淘汰紧随其后的工具执行结果，避免产生孤儿消息
 * </ul>
 *
 * <p>参照 LangChain4j 的 {@code MessageWindowChatMemory} 设计：窗口只做裁剪，状态交给 {@code ChatMemoryStore}， 一次
 * add 只做一次 store 读取和一次 store 写回。
 */
public final class MessageWindowChatMemory implements ChatMemory {

    private static final Object DEFAULT_ID = "default";

    private final Object id;
    private final int maxMessages;
    private final ChatMemoryStore store;

    private MessageWindowChatMemory(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id");
        if (builder.maxMessages == null || builder.maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages must be greater than zero");
        }
        this.maxMessages = builder.maxMessages;
        this.store = builder.store != null ? builder.store : new InMemoryChatMemoryStore();
    }

    @Override
    public Object id() {
        return id;
    }

    @Override
    public void add(ChatMessage message) {
        add(new ChatMessage[] {message});
    }

    /** 批量追加消息的核心优化：一次性读取当前消息、合并新消息、统一裁剪，最后一次性写回，避免逐条追加造成多次 store 读写。 */
    public synchronized void add(ChatMessage... newMessages) {
        if (newMessages == null || newMessages.length == 0) {
            return;
        }
        List<ChatMessage> currentMessages = store.getMessages(id);
        List<ChatMessage> allMessages = new LinkedList<ChatMessage>(currentMessages);

        for (ChatMessage message : newMessages) {
            if (message == null) {
                continue;
            }
            if (message.type() == ChatMessageType.SYSTEM) {
                int existingSystem = firstSystemIndex(allMessages);
                if (existingSystem >= 0) {
                    if (allMessages.get(existingSystem).equals(message)) {
                        continue;
                    }
                    allMessages.remove(existingSystem);
                }
            }
            allMessages.add(message);
        }

        ensureCapacity(allMessages);

        store.updateMessages(id, allMessages);
    }

    public synchronized void add(Iterable<? extends ChatMessage> newMessages) {
        if (newMessages == null) {
            return;
        }
        List<ChatMessage> batch = new ArrayList<ChatMessage>();
        for (ChatMessage message : newMessages) {
            batch.add(message);
        }
        add(batch.toArray(new ChatMessage[batch.size()]));
    }

    @Override
    public synchronized List<ChatMessage> messages() {
        List<ChatMessage> messages = new LinkedList<ChatMessage>(store.getMessages(id));
        ensureCapacity(messages);
        return Collections.unmodifiableList(new ArrayList<ChatMessage>(messages));
    }

    @Override
    public synchronized void clear() {
        store.deleteMessages(id);
    }

    private void ensureCapacity(List<ChatMessage> messages) {
        while (messages.size() > maxMessages) {
            int messageToEvictIndex =
                    !messages.isEmpty() && messages.get(0).type() == ChatMessageType.SYSTEM ? 1 : 0;
            if (messageToEvictIndex >= messages.size()) {
                return;
            }
            ChatMessage evicted = messages.remove(messageToEvictIndex);
            if (evicted instanceof AiMessage && ((AiMessage) evicted).hasToolExecutionRequests()) {
                while (messages.size() > messageToEvictIndex
                        && messages.get(messageToEvictIndex).type()
                                == ChatMessageType.TOOL_EXECUTION_RESULT) {
                    messages.remove(messageToEvictIndex);
                }
            }
        }
    }

    private int firstSystemIndex(List<ChatMessage> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).type() == ChatMessageType.SYSTEM) {
                return i;
            }
        }
        return -1;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static MessageWindowChatMemory withMaxMessages(int maxMessages) {
        return builder().maxMessages(maxMessages).build();
    }

    public static final class Builder {

        private Object id = DEFAULT_ID;
        private Integer maxMessages;
        private ChatMemoryStore store;

        private Builder() {}

        public Builder id(Object id) {
            this.id = id;
            return this;
        }

        public Builder maxMessages(Integer maxMessages) {
            this.maxMessages = maxMessages;
            return this;
        }

        /** 指定记忆状态的存储实现；不设置时默认使用进程内 {@link InMemoryChatMemoryStore} */
        public Builder chatMemoryStore(ChatMemoryStore store) {
            this.store = store;
            return this;
        }

        public MessageWindowChatMemory build() {
            return new MessageWindowChatMemory(this);
        }
    }
}
