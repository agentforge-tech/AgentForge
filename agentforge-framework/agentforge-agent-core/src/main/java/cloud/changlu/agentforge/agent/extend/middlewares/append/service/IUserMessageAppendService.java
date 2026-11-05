package cloud.changlu.agentforge.agent.extend.middlewares.append.service;

import cloud.changlu.agentforge.agent.extend.middlewares.append.domain.AppendedUserMessage;
import cloud.changlu.agentforge.agent.extend.middlewares.append.domain.UserMessageAppendResult;

import java.util.List;
import java.util.Optional;

/** Agent Run 运行期间追加用户消息的通用服务接口。 */
public interface IUserMessageAppendService {
    boolean open(Object memoryId);

    UserMessageAppendResult append(Object memoryId, String content);

    List<AppendedUserMessage> drain(Object memoryId);

    Optional<AppendedUserMessage> cancel(Object memoryId, Long appendId);

    List<AppendedUserMessage> closeAndDrain(Object memoryId);

    void clear(Object memoryId);

    int size(Object memoryId);
}
