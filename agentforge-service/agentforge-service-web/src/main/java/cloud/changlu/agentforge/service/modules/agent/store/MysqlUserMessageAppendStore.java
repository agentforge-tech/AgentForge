package cloud.changlu.agentforge.service.modules.agent.store;

import cloud.changlu.agentforge.agent.extend.middlewares.append.domain.AppendedUserMessage;
import cloud.changlu.agentforge.agent.extend.middlewares.append.store.AbstractUserMessageAppendStore;
import cloud.changlu.agentforge.service.modules.agent.dao.UserMessageAppendMapper;
import cloud.changlu.agentforge.service.modules.agent.domain.pojo.UserMessageAppendPojo;
import cloud.changlu.agentforge.service.modules.agent.enums.UserMessageAppendStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 默认 MySQL 扩展，只实现追加消息的基础 CRUD。 */
public class MysqlUserMessageAppendStore extends AbstractUserMessageAppendStore {
    private final UserMessageAppendMapper mapper;

    public MysqlUserMessageAppendStore(UserMessageAppendMapper mapper) {
        super(10);
        this.mapper = mapper;
    }

    @Override
    protected AppendedUserMessage insertMessage(Object memoryId, AppendedUserMessage message) {
        UserMessageAppendPojo pojo = new UserMessageAppendPojo();
        pojo.setSessionId(key(memoryId));
        pojo.setContent(message.getContent());
        pojo.setStatus(UserMessageAppendStatus.QUEUED.getCode());
        pojo.setQueuedAt(new java.util.Date(message.getQueuedAt()));
        mapper.insert(pojo);
        return new AppendedUserMessage(pojo.getId(), message.getContent(), message.getQueuedAt());
    }

    @Override
    protected List<AppendedUserMessage> listMessages(Object memoryId) {
        List<AppendedUserMessage> messages = new ArrayList<>();
        for (UserMessageAppendPojo pojo :
                mapper.selectPending(key(memoryId), UserMessageAppendStatus.QUEUED.getCode())) {
            messages.add(toMessage(pojo));
        }
        return messages;
    }

    @Override
    protected Optional<AppendedUserMessage> removeMessage(Object memoryId, Long appendId) {
        String sessionId = key(memoryId);
        int queuedStatus = UserMessageAppendStatus.QUEUED.getCode();
        UserMessageAppendPojo pojo = mapper.selectPendingById(sessionId, appendId, queuedStatus);
        if (pojo == null || mapper.deletePendingById(sessionId, appendId, queuedStatus) == 0) {
            return Optional.empty();
        }
        return Optional.of(toMessage(pojo));
    }

    @Override
    protected void deleteMessages(Object memoryId, List<AppendedUserMessage> messages) {
        List<Long> ids = new ArrayList<>();
        for (AppendedUserMessage message : messages) ids.add(message.getAppendId());
        mapper.deleteByIds(ids);
    }

    @Override
    protected void clearMessages(Object memoryId) {
        mapper.deleteBySessionId(key(memoryId));
    }

    @Override
    protected int countMessages(Object memoryId) {
        return mapper.countPending(key(memoryId), UserMessageAppendStatus.QUEUED.getCode());
    }

    private static AppendedUserMessage toMessage(UserMessageAppendPojo pojo) {
        return new AppendedUserMessage(
                pojo.getId(), pojo.getContent(), pojo.getQueuedAt().getTime());
    }

    private static String key(Object memoryId) {
        return String.valueOf(memoryId);
    }
}
