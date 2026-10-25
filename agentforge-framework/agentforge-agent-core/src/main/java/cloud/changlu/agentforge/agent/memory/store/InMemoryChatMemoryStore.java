package cloud.changlu.agentforge.agent.memory.store;

import cloud.changlu.agentforge.model.chat.message.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @description 进程内 ChatMemoryStore 实现，按 memoryId 保存消息列表，供单机与测试场景使用。
 *     <p>写入时对消息做防御性拷贝，读取时返回不可变视图，避免调用方直接持有或修改内部集合。
 * @author changlu
 * @date 2026/10/25
 */
public final class InMemoryChatMemoryStore implements ChatMemoryStore {

    private final Map<Object, List<ChatMessage>> messagesByMemoryId =
            new ConcurrentHashMap<Object, List<ChatMessage>>();

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        List<ChatMessage> messages = messagesByMemoryId.get(memoryId);
        if (messages == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<ChatMessage>(messages));
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            messagesByMemoryId.remove(memoryId);
            return;
        }
        messagesByMemoryId.put(memoryId, new ArrayList<ChatMessage>(messages));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        messagesByMemoryId.remove(memoryId);
    }
}
