package cloud.changlu.agentforge.service.modules.agent.event.store.mysql;

import cloud.changlu.agentforge.service.modules.agent.event.store.MessageEventStore;
import cloud.changlu.agentforge.service.modules.agent.event.store.MessageEventTailer;
import cloud.changlu.agentforge.service.modules.agent.event.store.StoredMessageEvent;

import java.util.Date;
import java.util.List;

/** 使用关系表追加和游标查询模拟 Stream。 */
public class MysqlMessageEventStore implements MessageEventStore, MessageEventTailer {
    private final MessageEventMapper mapper;

    public MysqlMessageEventStore(MessageEventMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public StoredMessageEvent append(
            long messageId, long eventSeq, String eventType, String payload) {
        StoredMessageEvent event = new StoredMessageEvent();
        event.setMessageId(messageId);
        event.setEventSeq(eventSeq);
        event.setEventType(eventType);
        event.setPayload(payload);
        event.setCreateTime(new Date());
        mapper.insert(event);
        return event;
    }

    @Override
    public List<StoredMessageEvent> readAfter(long messageId, long afterSeq, int limit) {
        return mapper.selectAfter(messageId, afterSeq, limit);
    }

    @Override
    public List<StoredMessageEvent> awaitAfter(
            long messageId, long afterSeq, int limit, long waitMillis) {
        List<StoredMessageEvent> events = readAfter(messageId, afterSeq, limit);
        if (!events.isEmpty() || waitMillis <= 0L) return events;
        try {
            Thread.sleep(waitMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return events;
        }
        return readAfter(messageId, afterSeq, limit);
    }

    @Override
    public List<StoredMessageEvent> readAll(long messageId) {
        return mapper.selectAll(messageId);
    }

    @Override
    public long maxSeq(long messageId) {
        Long value = mapper.selectMaxSeq(messageId);
        return value == null ? 0L : value;
    }

    @Override
    public void deleteByMessageId(long messageId) {
        mapper.deleteByMessageId(messageId);
    }
}
