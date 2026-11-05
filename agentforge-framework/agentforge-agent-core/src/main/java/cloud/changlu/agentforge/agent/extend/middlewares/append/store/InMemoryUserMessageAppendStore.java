package cloud.changlu.agentforge.agent.extend.middlewares.append.store;

import cloud.changlu.agentforge.agent.extend.middlewares.append.domain.AppendedUserMessage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 追加消息的内存存储实现。 */
public final class InMemoryUserMessageAppendStore extends AbstractUserMessageAppendStore {
    private final ConcurrentHashMap<Object, ArrayDeque<AppendedUserMessage>> queues =
            new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    public InMemoryUserMessageAppendStore(int maxPending) {
        super(maxPending);
    }

    @Override
    protected AppendedUserMessage insertMessage(Object memoryId, AppendedUserMessage message) {
        AppendedUserMessage stored =
                message.getAppendId() == null
                        ? new AppendedUserMessage(
                                ids.incrementAndGet(), message.getContent(), message.getQueuedAt())
                        : message;
        queues.computeIfAbsent(memoryId, key -> new ArrayDeque<>()).offerLast(stored);
        return stored;
    }

    @Override
    protected List<AppendedUserMessage> listMessages(Object memoryId) {
        ArrayDeque<AppendedUserMessage> queue = queues.get(memoryId);
        return queue == null ? new ArrayList<>() : new ArrayList<>(queue);
    }

    @Override
    protected Optional<AppendedUserMessage> removeMessage(Object memoryId, Long appendId) {
        ArrayDeque<AppendedUserMessage> queue = queues.get(memoryId);
        if (queue == null) return Optional.empty();
        Iterator<AppendedUserMessage> iterator = queue.iterator();
        while (iterator.hasNext()) {
            AppendedUserMessage message = iterator.next();
            if (appendId.equals(message.getAppendId())) {
                iterator.remove();
                return Optional.of(message);
            }
        }
        return Optional.empty();
    }

    @Override
    protected void deleteMessages(Object memoryId, List<AppendedUserMessage> messages) {
        ArrayDeque<AppendedUserMessage> queue = queues.get(memoryId);
        if (queue != null) queue.clear();
    }

    @Override
    protected void clearMessages(Object memoryId) {
        queues.remove(memoryId);
    }

    @Override
    protected int countMessages(Object memoryId) {
        ArrayDeque<AppendedUserMessage> queue = queues.get(memoryId);
        return queue == null ? 0 : queue.size();
    }
}
