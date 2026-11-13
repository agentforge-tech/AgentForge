package cloud.changlu.agentforge.service.modules.agent.event.sink;

import cloud.changlu.agentforge.service.protocol.StreamEventResponse;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 对 {@link SseEmitter} 的展示型事件出口封装；客户端断开后静默停止发送。 */
public final class SseEmitterEventSink implements StreamEventSink {

    private final SseEmitter emitter;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public SseEmitterEventSink(SseEmitter emitter) {
        this.emitter = emitter;
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
    }

    @Override
    public void publish(StreamEventResponse event) {
        if (closed.get()) return;
        try {
            emitter.send(SseEmitter.event().data(event));
        } catch (IOException | IllegalStateException ignored) {
            closed.set(true);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 客户端可能已断开。
            }
        }
    }
}
