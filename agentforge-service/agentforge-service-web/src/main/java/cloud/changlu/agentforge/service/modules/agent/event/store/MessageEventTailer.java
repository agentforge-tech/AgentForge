package cloud.changlu.agentforge.service.modules.agent.event.store;

import java.util.List;

/** 读取消息事件尾部的等待语义；MySQL 使用轮询，Redis 可直接实现为 XREAD BLOCK。 */
public interface MessageEventTailer {
    List<StoredMessageEvent> awaitAfter(long messageId, long afterSeq, int limit, long waitMillis);
}
