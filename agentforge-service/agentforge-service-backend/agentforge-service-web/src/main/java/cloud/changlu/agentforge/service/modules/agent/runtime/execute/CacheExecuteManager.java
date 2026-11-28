package cloud.changlu.agentforge.service.modules.agent.runtime.execute;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行态注册表：sessionId + messageId + executionId。
 *
 * <p>用于回答"某会话的某条消息是不是正在执行"，目前服务启动恢复依赖它判断是否需要接管； 后续定时任务等新入口可以直接复用同一套登记方式。
 *
 * <p>注册表是进程内的，服务重启后必然为空——这正是"重启后需要恢复"的语义前提。
 */
@Component
public class CacheExecuteManager {

    /** key = messageId（全局唯一主键），值里带全部三个 ID + 取消句柄。 */
    private final Map<Long, Execute> executes = new ConcurrentHashMap<Long, Execute>();

    /** 登记一次执行；该消息已在跑则返回 false（调用方据此拒绝或跳过）。 */
    public boolean register(String sessionId, Long messageId, String executionId, Runnable cancel) {
        if (sessionId == null || messageId == null) {
            return false;
        }
        return executes.putIfAbsent(
                        messageId, new Execute(sessionId, messageId, executionId, cancel))
                == null;
    }

    /** 该会话的这条消息是否正在跑；会话与消息对不上也算没在跑。 */
    public boolean isRunning(String sessionId, Long messageId) {
        if (sessionId == null || messageId == null) {
            return false;
        }
        Execute execute = executes.get(messageId);
        return execute != null && sessionId.equals(execute.sessionId);
    }

    /** 注销，返回是否命中；任务收口、取消、异常兜底都调它。 */
    public boolean unregister(Long messageId) {
        return messageId != null && executes.remove(messageId) != null;
    }

    /** 取取消句柄并注销（用户点停止时用）；没在跑返回 null。 */
    public Runnable takeCancel(Long messageId) {
        Execute execute = messageId == null ? null : executes.remove(messageId);
        return execute == null ? null : execute.cancel;
    }

    /** 当前登记中的执行数量，供日志与排查使用。 */
    public int size() {
        return executes.size();
    }

    /** 一次执行：三个 ID + 取消句柄。 */
    private static final class Execute {
        private final String sessionId;
        private final Long messageId;
        private final String executionId;
        private final Runnable cancel;

        private Execute(String sessionId, Long messageId, String executionId, Runnable cancel) {
            this.sessionId = sessionId;
            this.messageId = messageId;
            this.executionId = executionId;
            this.cancel = cancel;
        }
    }
}
