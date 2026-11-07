package cloud.changlu.agentforge.service.modules.agent.event.sink.store;

import cloud.changlu.agentforge.service.modules.agent.event.sink.StreamEventSink;
import cloud.changlu.agentforge.service.modules.agent.event.store.MessageEventStore;
import cloud.changlu.agentforge.service.protocol.StreamEventResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 后台对话的缓冲型生产出口；合并高频文本增量后写入当前配置的 EventStore。 */
public final class BufferedMessageEventSink implements StreamEventSink {

    private static final int MAX_MERGED_CHUNKS = 12;
    private static final int MAX_MERGED_CHARS = 1024;
    private static final long MAX_BUFFER_MILLIS = 80L;

    private final long messageId;
    private final MessageEventStore eventStore;
    private final ObjectMapper objectMapper;
    private long nextSeq;
    private StreamEventResponse bufferedDelta;
    private int bufferedChunks;
    private long bufferedAt;

    public BufferedMessageEventSink(
            long messageId, MessageEventStore eventStore, ObjectMapper objectMapper) {
        this.messageId = messageId;
        this.eventStore = eventStore;
        this.objectMapper = objectMapper;
        this.nextSeq = eventStore.maxSeq(messageId);
    }

    @Override
    public synchronized void publish(StreamEventResponse event) {
        // 模型通常按 token 高频产生 resp/think 文本增量。先在内存中合并相邻片段，
        // 达到阈值后再写 MySQL，避免每个 token 执行一次 INSERT 造成明显写放大。
        if (isMergeableDelta(event)) {
            appendDelta(event);
            return;
        }

        // 工具、错误、结束等非增量事件属于流中的语义边界。必须先落下此前缓存的
        // 文本，再写当前边界事件，保证 eventSeq 与 Agent 实际产生事件的顺序一致。
        flush();
        append(copyOf(event));
    }

    @Override
    public synchronized void close() {
        flush();
    }

    private void appendDelta(StreamEventResponse event) {
        // resp 与 think，或 type/title 不同的增量不能跨组拼接；切组前先落旧缓冲。
        if (bufferedDelta == null || !sameDeltaGroup(bufferedDelta, event)) {
            flush();
            bufferedDelta = copyOf(event);
            bufferedChunks = 1;
            bufferedAt = System.currentTimeMillis();
        } else {
            bufferedDelta.setContent(
                    String.valueOf(bufferedDelta.getContent())
                            + String.valueOf(event.getContent()));
            bufferedChunks++;
        }
        int chars = String.valueOf(bufferedDelta.getContent()).length();
        // 同时限制片段数、合并文本大小和等待时间，平衡数据库写入次数与前端可见延迟。
        if (bufferedChunks >= MAX_MERGED_CHUNKS
                || chars >= MAX_MERGED_CHARS
                || System.currentTimeMillis() - bufferedAt >= MAX_BUFFER_MILLIS) {
            flush();
        }
    }

    private void flush() {
        if (bufferedDelta == null) return;
        StreamEventResponse event = bufferedDelta;
        bufferedDelta = null;
        bufferedChunks = 0;
        bufferedAt = 0L;
        append(event);
    }

    private void append(StreamEventResponse event) {
        long seq = ++nextSeq;
        event.setEventSeq(seq);
        try {
            eventStore.append(
                    messageId, seq, event.getEvent(), objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            throw new IllegalStateException("流式事件暂存失败, messageId=" + messageId, e);
        }
    }

    private static boolean isMergeableDelta(StreamEventResponse event) {
        if (event == null || !(event.getContent() instanceof String)) return false;
        if (Boolean.TRUE.equals(event.getIsFinish())) return false;
        return "resp".equals(event.getEvent()) || "think".equals(event.getEvent());
    }

    private static boolean sameDeltaGroup(StreamEventResponse left, StreamEventResponse right) {
        return equals(left.getEvent(), right.getEvent())
                && equals(left.getType(), right.getType())
                && equals(left.getTitle(), right.getTitle());
    }

    private static boolean equals(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
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
