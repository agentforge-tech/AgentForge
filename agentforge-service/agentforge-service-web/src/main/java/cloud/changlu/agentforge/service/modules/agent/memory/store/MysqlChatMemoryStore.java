package cloud.changlu.agentforge.service.modules.agent.memory.store;

import cloud.changlu.agentforge.agent.memory.store.ChatMemoryStore;
import cloud.changlu.agentforge.agent.memory.store.ChatMessageCodec;
import cloud.changlu.agentforge.model.chat.message.ChatMessage;
import cloud.changlu.agentforge.service.modules.agent.memory.dao.AgentMemoryMapper;
import cloud.changlu.agentforge.service.modules.agent.memory.domain.AgentMemoryPojo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * 基于 {@code ai_agent_memrory} 的会话记忆存储实现，一个 memoryId 对应一行，content 存整段消息 JSON。
 *
 * <p>写入采用「先更新、影响 0 行再插入」的方式，避免 {@code ON DUPLICATE KEY UPDATE} 带来的自增跳号与 create_time 语义模糊。
 *
 * <p>落库异常只记录日志不向上抛出：记忆属于尽力而为的数据，不应因为一次数据库抖动导致整轮对话失败。
 *
 * @author changlu
 * @since 2026-10-25
 */
public class MysqlChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MysqlChatMemoryStore.class);

    private final AgentMemoryMapper agentMemoryMapper;
    private final ChatMessageCodec chatMessageCodec = new ChatMessageCodec();

    public MysqlChatMemoryStore(AgentMemoryMapper agentMemoryMapper) {
        this.agentMemoryMapper = agentMemoryMapper;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String memoryIdValue = toMemoryId(memoryId);
        AgentMemoryPojo pojo = agentMemoryMapper.selectByMemoryId(memoryIdValue);
        if (pojo == null || pojo.getContent() == null) {
            return Collections.emptyList();
        }
        try {
            return chatMessageCodec.decodeAll(pojo.getContent());
        } catch (RuntimeException e) {
            log.warn("Failed to decode chat memory, memoryId={}", memoryIdValue, e);
            return Collections.emptyList();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String memoryIdValue = toMemoryId(memoryId);
        if (messages == null || messages.isEmpty()) {
            deleteMessages(memoryIdValue);
            return;
        }
        String content = chatMessageCodec.encodeAll(messages);
        try {
            int updated = agentMemoryMapper.updateByMemoryId(memoryIdValue, content);
            if (updated == 0) {
                agentMemoryMapper.insert(memoryIdValue, content);
            }
        } catch (RuntimeException e) {
            log.warn(
                    "Failed to persist chat memory, memoryId={}, size={}",
                    memoryIdValue,
                    messages.size(),
                    e);
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String memoryIdValue = toMemoryId(memoryId);
        try {
            agentMemoryMapper.deleteByMemoryId(memoryIdValue);
        } catch (RuntimeException e) {
            log.warn("Failed to delete chat memory, memoryId={}", memoryIdValue, e);
        }
    }

    private String toMemoryId(Object memoryId) {
        return memoryId == null ? "default" : String.valueOf(memoryId);
    }
}
