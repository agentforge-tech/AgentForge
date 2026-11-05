package cloud.changlu.agentforge.service.modules.agent.service.impl;

import cloud.changlu.agentforge.agent.extend.middlewares.append.domain.AppendedUserMessage;
import cloud.changlu.agentforge.agent.extend.middlewares.append.domain.UserMessageAppendResult;
import cloud.changlu.agentforge.agent.extend.middlewares.append.service.IUserMessageAppendService;
import cloud.changlu.agentforge.agent.extend.middlewares.append.store.UserMessageAppendStore;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Agent 与 HTTP 入口共享的追加消息门面。 */
@Service
public class UserMessageAppendServiceImpl implements IUserMessageAppendService {
    private final UserMessageAppendStore store;

    public UserMessageAppendServiceImpl(UserMessageAppendStore store) {
        this.store = store;
    }

    @Override
    @Transactional
    public boolean open(Object memoryId) {
        return store.open(memoryId);
    }

    @Override
    @Transactional
    public UserMessageAppendResult append(Object memoryId, String content) {
        if (memoryId == null || content == null || content.trim().isEmpty()) {
            return UserMessageAppendResult.rejected(UserMessageAppendResult.Code.INVALID_ARGUMENT);
        }
        String normalized = content.trim();
        return store.enqueue(
                memoryId, new AppendedUserMessage(null, normalized, System.currentTimeMillis()));
    }

    @Override
    @Transactional
    public List<AppendedUserMessage> drain(Object memoryId) {
        return store.drain(memoryId);
    }

    @Override
    @Transactional
    public Optional<AppendedUserMessage> cancel(Object memoryId, Long appendId) {
        return store.remove(memoryId, appendId);
    }

    @Override
    @Transactional
    public List<AppendedUserMessage> closeAndDrain(Object memoryId) {
        return store.closeAndDrain(memoryId);
    }

    @Override
    @Transactional
    public void clear(Object memoryId) {
        store.clear(memoryId);
    }

    @Override
    @Transactional(readOnly = true)
    public int size(Object memoryId) {
        return store.size(memoryId);
    }
}
