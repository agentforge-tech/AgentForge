package cloud.changlu.agentforge.service.modules.agent.service.impl;

import cloud.changlu.agentforge.service.modules.agent.event.store.MessageEventStore;
import cloud.changlu.agentforge.service.modules.agent.event.store.MessageEventTailer;
import cloud.changlu.agentforge.service.modules.agent.event.store.StoredMessageEvent;
import cloud.changlu.agentforge.service.modules.session.dao.AgentSessionMessageMapper;
import cloud.changlu.agentforge.service.modules.session.domain.AgentSessionMessagePojo;
import cloud.changlu.agentforge.service.modules.session.enums.SessionMessageRoleEnum;
import cloud.changlu.agentforge.service.modules.session.enums.SessionStatusTransfer;
import cloud.changlu.agentforge.service.modules.session.service.IAgentSessionService;
import cloud.changlu.agentforge.service.protocol.StreamEventResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ReconnectableChatStreamService {
    private static final int BATCH_SIZE = 100;
    private static final long POLL_MS = 200L;
    private static final long HEARTBEAT_MS = 15_000L;

    private final MessageEventStore eventStore;
    private final MessageEventTailer eventTailer;
    private final AgentSessionMessageMapper messageMapper;
    private final IAgentSessionService sessionService;
    private final MessageEventSnapshotService snapshotService;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    public ReconnectableChatStreamService(
            MessageEventStore eventStore,
            MessageEventTailer eventTailer,
            AgentSessionMessageMapper messageMapper,
            IAgentSessionService sessionService,
            MessageEventSnapshotService snapshotService,
            ObjectMapper objectMapper,
            @Qualifier("chatStreamExecutor") Executor executor) {
        this.eventStore = eventStore;
        this.eventTailer = eventTailer;
        this.messageMapper = messageMapper;
        this.sessionService = sessionService;
        this.snapshotService = snapshotService;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    public SseEmitter subscribe(long messageId, long afterSeq, long userId) {
        AgentSessionMessagePojo message = requireOwnedAiMessage(messageId, userId);
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(error -> closed.set(true));
        executor.execute(() -> stream(emitter, closed, message, Math.max(0L, afterSeq)));
        return emitter;
    }

    private void stream(
            SseEmitter emitter,
            AtomicBoolean closed,
            AgentSessionMessagePojo initial,
            long cursor) {
        long messageId = initial.getId();
        long lastHeartbeat = System.currentTimeMillis();
        try {
            while (!closed.get()) {
                List<StoredMessageEvent> batch =
                        eventTailer.awaitAfter(messageId, cursor, BATCH_SIZE, POLL_MS);
                if (!batch.isEmpty()) {
                    for (StoredMessageEvent stored : batch) {
                        StreamEventResponse event =
                                objectMapper.readValue(
                                        stored.getPayload(), StreamEventResponse.class);
                        emitter.send(
                                SseEmitter.event()
                                        .id(String.valueOf(stored.getEventSeq()))
                                        .data(event));
                        cursor = stored.getEventSeq();
                    }
                    continue;
                }
                AgentSessionMessagePojo message = messageMapper.selectById(messageId);
                if (message == null) break;
                if (isTerminal(message.getStatus())) {
                    // 观察到终态后再读一轮，排空与终态紧邻的尾事件。
                    if (eventStore.readAfter(messageId, cursor, 1).isEmpty()) {
                        if (message.getEvents() == null || message.getEvents().trim().isEmpty()) {
                            snapshotService.saveSnapshot(messageId);
                        }
                        break;
                    }
                    continue;
                }
                long now = System.currentTimeMillis();
                if (now - lastHeartbeat >= HEARTBEAT_MS) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    lastHeartbeat = now;
                }
            }
            if (!closed.get()) emitter.complete();
        } catch (Exception e) {
            if (!closed.get()) emitter.completeWithError(e);
        }
    }

    private AgentSessionMessagePojo requireOwnedAiMessage(long messageId, long userId) {
        AgentSessionMessagePojo message = messageMapper.selectById(messageId);
        if (message == null || !SessionMessageRoleEnum.AI.getCode().equals(message.getRoleType())) {
            throw new IllegalArgumentException("AI消息不存在或已被删除");
        }
        sessionService.requireOwned(message.getSessionId(), userId);
        return message;
    }

    private static boolean isTerminal(Integer status) {
        return SessionStatusTransfer.isTerminal(status);
    }
}
