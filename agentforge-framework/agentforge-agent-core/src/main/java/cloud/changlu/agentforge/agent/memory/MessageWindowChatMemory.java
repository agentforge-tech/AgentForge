package cloud.changlu.agentforge.agent.memory;

import cloud.changlu.agentforge.model.chat.message.AiMessage;
import cloud.changlu.agentforge.model.chat.message.ChatMessage;
import cloud.changlu.agentforge.model.chat.message.ChatMessageType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 支持批量写入的消息窗口记忆。系统消息始终位于窗口中；淘汰带工具调用的 AI 消息时，会同步淘汰紧随其后的工具执行结果，避免产生孤儿消息。 */
public final class MessageWindowChatMemory implements ChatMemory {

    private static final Object DEFAULT_ID = "default";

    private final Object id;
    private final int maxMessages;
    private final List<ChatMessage> messages = new ArrayList<ChatMessage>();

    private MessageWindowChatMemory(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id");
        if (builder.maxMessages == null || builder.maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages must be greater than zero");
        }
        this.maxMessages = builder.maxMessages;
    }

    @Override
    public Object id() {
        return id;
    }

    @Override
    public void add(ChatMessage message) {
        add(new ChatMessage[] {message});
    }

    /** 批量追加消息的核心优化：在一次加锁范围内完成消息合并，并在所有消息加入后统一执行一次窗口裁剪，避免逐条追加造成重复加锁和重复容量检查。 */
    public synchronized void add(ChatMessage... newMessages) {
        if (newMessages == null || newMessages.length == 0) {
            return;
        }
        for (ChatMessage message : newMessages) {
            if (message == null) {
                continue;
            }
            if (message.type() == ChatMessageType.SYSTEM) {
                int existingSystem = firstSystemIndex();
                if (existingSystem >= 0) {
                    if (messages.get(existingSystem).equals(message)) {
                        continue;
                    }
                    messages.remove(existingSystem);
                }
            }
            messages.add(message);
        }
        ensureCapacity();
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
        ensureCapacity();
        return Collections.unmodifiableList(new ArrayList<ChatMessage>(messages));
    }

    @Override
    public synchronized void clear() {
        messages.clear();
    }

    private void ensureCapacity() {
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

    private int firstSystemIndex() {
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

        private Builder() {}

        public Builder id(Object id) {
            this.id = id;
            return this;
        }

        public Builder maxMessages(Integer maxMessages) {
            this.maxMessages = maxMessages;
            return this;
        }

        public MessageWindowChatMemory build() {
            return new MessageWindowChatMemory(this);
        }
    }
}
