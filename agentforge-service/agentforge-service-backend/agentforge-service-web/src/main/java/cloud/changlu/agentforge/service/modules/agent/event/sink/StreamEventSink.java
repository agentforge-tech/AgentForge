package cloud.changlu.agentforge.service.modules.agent.event.sink;

import cloud.changlu.agentforge.service.protocol.StreamEventResponse;

/** 流式事件统一出口。实时 SSE 与可重连暂存只替换 Sink，不侵入 Agent Middleware。 */
public interface StreamEventSink {

    void publish(StreamEventResponse event);

    default void close() {}
}
