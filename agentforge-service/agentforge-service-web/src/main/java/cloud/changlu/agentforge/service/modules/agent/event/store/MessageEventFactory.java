package cloud.changlu.agentforge.service.modules.agent.event.store;

import cloud.changlu.agentforge.service.env.EnvironmentContext;
import cloud.changlu.agentforge.service.modules.agent.event.sink.StreamEventSink;
import cloud.changlu.agentforge.service.modules.agent.event.sink.memory.InMemoryMessageEventSink;
import cloud.changlu.agentforge.service.modules.agent.event.sink.store.BufferedMessageEventSink;
import cloud.changlu.agentforge.service.modules.agent.event.store.memory.InMemoryMessageEventStore;
import cloud.changlu.agentforge.service.modules.agent.event.store.mysql.MessageEventMapper;
import cloud.changlu.agentforge.service.modules.agent.event.store.mysql.MysqlMessageEventStore;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 根据运行环境选择消息事件存储，并以统一 Store/Tailer SPI 暴露给生产端和消费端。
 *
 * <p>当前支持 MySQL 与进程内存；Redis 名称已预留，接入实现前选择 redis 会在启动期明确失败。
 */
@Component
public class MessageEventFactory implements MessageEventStore, MessageEventTailer {

    private final MessageEventStore store;
    private final MessageEventTailer tailer;
    private final ObjectMapper objectMapper;
    private final String type;

    public MessageEventFactory(
            EnvironmentContext environmentContext,
            MessageEventMapper messageEventMapper,
            ObjectMapper objectMapper) {
        this.type = environmentContext.getChatEventStoreType();
        this.objectMapper = objectMapper;
        MessageEventStore selected = create(type, messageEventMapper);
        this.store = selected;
        this.tailer = (MessageEventTailer) selected;
    }

    /** 为一轮后台对话创建与当前 Store 模式匹配的生产 Sink。 */
    public StreamEventSink createSink(long messageId) {
        if ("mysql".equals(type)) {
            return new BufferedMessageEventSink(messageId, store, objectMapper);
        }
        if ("inmemory".equals(type)) {
            return new InMemoryMessageEventSink(messageId, store, objectMapper);
        }
        // 构造阶段已校验，保留防御分支避免未来扩展时漏配 Sink。
        throw new IllegalStateException("尚未配置对应的 MessageEventSink: " + type);
    }

    private static MessageEventStore create(String type, MessageEventMapper mapper) {
        if ("mysql".equals(type)) {
            return new MysqlMessageEventStore(mapper);
        }
        if ("inmemory".equals(type)) {
            return new InMemoryMessageEventStore();
        }
        if ("redis".equals(type)) {
            throw new IllegalStateException("Redis MessageEventStore 尚未实现，请先使用 mysql 或 inmemory");
        }
        throw new IllegalArgumentException("不支持的 agentforge.chat.event-store.type: " + type);
    }

    @Override
    public StoredMessageEvent append(
            long messageId, long eventSeq, String eventType, String payload) {
        return store.append(messageId, eventSeq, eventType, payload);
    }

    @Override
    public List<StoredMessageEvent> readAfter(long messageId, long afterSeq, int limit) {
        return store.readAfter(messageId, afterSeq, limit);
    }

    @Override
    public List<StoredMessageEvent> readAll(long messageId) {
        return store.readAll(messageId);
    }

    @Override
    public long maxSeq(long messageId) {
        return store.maxSeq(messageId);
    }

    @Override
    public void deleteByMessageId(long messageId) {
        store.deleteByMessageId(messageId);
    }

    @Override
    public List<StoredMessageEvent> awaitAfter(
            long messageId, long afterSeq, int limit, long waitMillis) {
        return tailer.awaitAfter(messageId, afterSeq, limit, waitMillis);
    }
}
