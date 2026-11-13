package cloud.changlu.agentforge.service.modules.agent.event.store.memory;

import cloud.changlu.agentforge.service.modules.agent.event.store.MessageEventStore;
import cloud.changlu.agentforge.service.modules.agent.event.store.MessageEventTailer;
import cloud.changlu.agentforge.service.modules.agent.event.store.StoredMessageEvent;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 仅用于本地开发和轻量测试的进程内事件存储。
 *
 * <p>数据不跨实例共享，服务重启即丢失，因此不能用于需要可靠重连的生产环境。
 */
public class InMemoryMessageEventStore implements MessageEventStore, MessageEventTailer {

    private final AtomicLong ids = new AtomicLong();
    private final ConcurrentMap<Long, ConcurrentSkipListMap<Long, StoredMessageEvent>> streams =
            new ConcurrentHashMap<Long, ConcurrentSkipListMap<Long, StoredMessageEvent>>();
    private final ConcurrentMap<Long, Object> signals = new ConcurrentHashMap<Long, Object>();

    @Override
    public StoredMessageEvent append(
            long messageId, long eventSeq, String eventType, String payload) {
        StoredMessageEvent event = new StoredMessageEvent();
        event.setId(ids.incrementAndGet());
        event.setMessageId(messageId);
        event.setEventSeq(eventSeq);
        event.setEventType(eventType);
        event.setPayload(payload);
        event.setCreateTime(new Date());
        streams.computeIfAbsent(messageId, ignored -> new ConcurrentSkipListMap<>())
                .put(eventSeq, event);
        Object signal = signals.computeIfAbsent(messageId, ignored -> new Object());
        synchronized (signal) {
            signal.notifyAll();
        }
        return event;
    }

    @Override
    public List<StoredMessageEvent> readAfter(long messageId, long afterSeq, int limit) {
        ConcurrentSkipListMap<Long, StoredMessageEvent> stream = streams.get(messageId);
        if (stream == null || limit <= 0) return new ArrayList<StoredMessageEvent>();
        NavigableMap<Long, StoredMessageEvent> tail = stream.tailMap(afterSeq, false);
        List<StoredMessageEvent> result = new ArrayList<StoredMessageEvent>();
        for (StoredMessageEvent event : tail.values()) {
            result.add(event);
            if (result.size() >= limit) break;
        }
        return result;
    }

    @Override
    public List<StoredMessageEvent> awaitAfter(
            long messageId, long afterSeq, int limit, long waitMillis) {
        List<StoredMessageEvent> events = readAfter(messageId, afterSeq, limit);
        if (!events.isEmpty() || waitMillis <= 0L) return events;
        Object signal = signals.computeIfAbsent(messageId, ignored -> new Object());
        synchronized (signal) {
            // 二次检查避免事件恰好在首次查询与进入 wait 之间到达而错过通知。
            events = readAfter(messageId, afterSeq, limit);
            if (!events.isEmpty()) return events;
            try {
                signal.wait(waitMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return readAfter(messageId, afterSeq, limit);
    }

    @Override
    public List<StoredMessageEvent> readAll(long messageId) {
        ConcurrentSkipListMap<Long, StoredMessageEvent> stream = streams.get(messageId);
        return stream == null
                ? new ArrayList<StoredMessageEvent>()
                : new ArrayList<StoredMessageEvent>(stream.values());
    }

    @Override
    public long maxSeq(long messageId) {
        ConcurrentSkipListMap<Long, StoredMessageEvent> stream = streams.get(messageId);
        return stream == null || stream.isEmpty() ? 0L : stream.lastKey();
    }

    @Override
    public void deleteByMessageId(long messageId) {
        streams.remove(messageId);
        signals.remove(messageId);
    }
}
