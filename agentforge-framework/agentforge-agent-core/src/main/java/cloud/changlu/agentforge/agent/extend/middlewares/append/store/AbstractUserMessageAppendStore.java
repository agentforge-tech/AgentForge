package cloud.changlu.agentforge.agent.extend.middlewares.append.store;

import cloud.changlu.agentforge.agent.extend.middlewares.append.domain.AppendedUserMessage;
import cloud.changlu.agentforge.agent.extend.middlewares.append.domain.UserMessageAppendResult;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** 统一追加窗口、会话锁、容量限制、FIFO 消费和关闭幂等；子类只实现存储 CRUD。 */
public abstract class AbstractUserMessageAppendStore implements UserMessageAppendStore {
    private final ConcurrentHashMap<Object, RunState> states = new ConcurrentHashMap<>();
    private final int maxPending;

    protected AbstractUserMessageAppendStore(int maxPending) {
        if (maxPending <= 0) throw new IllegalArgumentException("maxPending must be positive");
        this.maxPending = maxPending;
    }

    @Override
    public boolean open(Object memoryId) {
        if (memoryId == null) return false;
        RunState state = states.computeIfAbsent(memoryId, key -> new RunState());
        state.lock.lock();
        try {
            if (state.open) return false;
            clearMessages(memoryId);
            state.open = true;
            return true;
        } finally {
            state.lock.unlock();
        }
    }

    @Override
    public UserMessageAppendResult enqueue(Object memoryId, AppendedUserMessage message) {
        RunState state = memoryId == null ? null : states.get(memoryId);
        if (state == null)
            return UserMessageAppendResult.rejected(UserMessageAppendResult.Code.RUN_NOT_OPEN);
        state.lock.lock();
        try {
            if (!state.open)
                return UserMessageAppendResult.rejected(
                        UserMessageAppendResult.Code.RUN_ALREADY_CLOSED);
            int size = countMessages(memoryId);
            if (size >= maxPending)
                return UserMessageAppendResult.rejected(UserMessageAppendResult.Code.QUEUE_FULL);
            return UserMessageAppendResult.queued(insertMessage(memoryId, message), size + 1);
        } finally {
            state.lock.unlock();
        }
    }

    @Override
    public List<AppendedUserMessage> drain(Object memoryId) {
        RunState state = memoryId == null ? null : states.get(memoryId);
        if (state == null) return Collections.emptyList();
        state.lock.lock();
        try {
            return state.open ? drainMessages(memoryId) : Collections.emptyList();
        } finally {
            state.lock.unlock();
        }
    }

    @Override
    public Optional<AppendedUserMessage> remove(Object memoryId, Long appendId) {
        RunState state = memoryId == null ? null : states.get(memoryId);
        if (state == null || appendId == null) return Optional.empty();
        state.lock.lock();
        try {
            return state.open ? removeMessage(memoryId, appendId) : Optional.empty();
        } finally {
            state.lock.unlock();
        }
    }

    @Override
    public List<AppendedUserMessage> closeAndDrain(Object memoryId) {
        RunState state = memoryId == null ? null : states.get(memoryId);
        if (state == null) return Collections.emptyList();
        state.lock.lock();
        try {
            if (!state.open) return Collections.emptyList();
            state.open = false;
            return drainMessages(memoryId);
        } finally {
            state.lock.unlock();
        }
    }

    @Override
    public void clear(Object memoryId) {
        if (memoryId == null) return;
        RunState state = states.remove(memoryId);
        if (state == null) {
            clearMessages(memoryId);
            return;
        }
        state.lock.lock();
        try {
            state.open = false;
            clearMessages(memoryId);
        } finally {
            state.lock.unlock();
        }
    }

    @Override
    public int size(Object memoryId) {
        return memoryId == null ? 0 : countMessages(memoryId);
    }

    private List<AppendedUserMessage> drainMessages(Object memoryId) {
        List<AppendedUserMessage> messages = listMessages(memoryId);
        if (!messages.isEmpty()) deleteMessages(memoryId, messages);
        return messages;
    }

    protected abstract AppendedUserMessage insertMessage(
            Object memoryId, AppendedUserMessage message);

    protected abstract List<AppendedUserMessage> listMessages(Object memoryId);

    protected abstract Optional<AppendedUserMessage> removeMessage(Object memoryId, Long appendId);

    protected abstract void deleteMessages(Object memoryId, List<AppendedUserMessage> messages);

    protected abstract void clearMessages(Object memoryId);

    protected abstract int countMessages(Object memoryId);

    private static final class RunState {
        private final ReentrantLock lock = new ReentrantLock();
        private boolean open;
    }
}
