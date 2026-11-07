package cloud.changlu.agentforge.service.modules.agent.event.store;

import java.util.List;

/** 可替换的消息事件存储 SPI；第一阶段由 MySQL 实现，后续可替换 Redis Stream。 */
public interface MessageEventStore {
    StoredMessageEvent append(long messageId, long eventSeq, String eventType, String payload);

    List<StoredMessageEvent> readAfter(long messageId, long afterSeq, int limit);

    List<StoredMessageEvent> readAll(long messageId);

    long maxSeq(long messageId);

    void deleteByMessageId(long messageId);
}
