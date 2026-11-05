package cloud.changlu.agentforge.agent.extend.middlewares.append.store;

import cloud.changlu.agentforge.agent.extend.middlewares.append.domain.AppendedUserMessage;
import cloud.changlu.agentforge.agent.extend.middlewares.append.domain.UserMessageAppendResult;

import java.util.List;
import java.util.Optional;

/**
 * Agent Run 运行期间的用户追加消息存储。
 *
 * <p>保存尚未被模型读取的短生命周消息，不代表聊天历史。每个 {@code memoryId} 对应独立的运行窗口和 FIFO 队列： {@code open -> enqueue/remove
 * -> drain -> closeAndDrain}。
 */
public interface UserMessageAppendStore {

    /** 为一次 Agent Run 打开追加窗口；重复打开已开启的窗口时返回 {@code false}。 */
    boolean open(Object memoryId);

    /** 在检查窗口状态和队列容量后，原子地将消息追加到队尾。 */
    UserMessageAppendResult enqueue(Object memoryId, AppendedUserMessage message);

    /** 按 FIFO 顺序原子取走当前全部待处理消息，追加窗口保持开启。 */
    List<AppendedUserMessage> drain(Object memoryId);

    /** 取消一条尚未被消费的追加消息。 */
    Optional<AppendedUserMessage> remove(Object memoryId, Long appendId);

    /** 原子关闭追加窗口，并返回全部未消费消息；重复关闭返回空列表。 */
    List<AppendedUserMessage> closeAndDrain(Object memoryId);

    /** 强制清理指定会话的队列和窗口状态。 */
    void clear(Object memoryId);

    /** 返回当前尚未被消费或取消的消息数量。 */
    int size(Object memoryId);
}
