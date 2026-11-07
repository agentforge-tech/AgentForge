package cloud.changlu.agentforge.service.modules.agent.event.sink.memory;

import cloud.changlu.agentforge.service.modules.agent.event.sink.StreamEventSink;
import cloud.changlu.agentforge.service.modules.agent.event.store.MessageEventStore;
import cloud.changlu.agentforge.service.protocol.StreamEventResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

/** InMemory 模式的即时事件出口；内存写入开销低，无需合并 token 增量。 */
public final class InMemoryMessageEventSink implements StreamEventSink {

    private final long messageId;
    private final MessageEventStore eventStore;
    private final ObjectMapper objectMapper;
    private long nextSeq;

    public InMemoryMessageEventSink(
            long messageId, MessageEventStore eventStore, ObjectMapper objectMapper) {
        this.messageId = messageId;
        this.eventStore = eventStore;
        this.objectMapper = objectMapper;
        this.nextSeq = eventStore.maxSeq(messageId);
    }

    @Override
    public synchronized void publish(StreamEventResponse source) {
        StreamEventResponse event = copyOf(source);
        long seq = ++nextSeq;
        event.setEventSeq(seq);
        try {
            eventStore.append(
                    messageId, seq, event.getEvent(), objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            throw new IllegalStateException("内存流式事件写入失败, messageId=" + messageId, e);
        }
    }

    private static StreamEventResponse copyOf(StreamEventResponse source) {
        StreamEventResponse copy = new StreamEventResponse();
        copy.setEvent(source.getEvent());
        copy.setType(source.getType());
        copy.setTitle(source.getTitle());
        copy.setContent(source.getContent());
        copy.setIsFinish(source.getIsFinish());
        return copy;
    }
}
