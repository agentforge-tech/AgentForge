package cloud.changlu.agentforge.service.modules.agent.support;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 一段式对话的停止信号注册表：sessionId 映射到本机取消动作。 */
@Component
public class ChatStopRegistry {

    private final Map<String, Runnable> stops = new ConcurrentHashMap<String, Runnable>();

    /** 注册某个会话的停止动作；同会话重复注册以最后一次为准。 */
    public void register(String sessionKey, Runnable stop) {
        if (sessionKey != null && stop != null) {
            stops.put(sessionKey, stop);
        }
    }

    /** 移除停止动作，用于任务正常结束后的清理。 */
    public void remove(String sessionKey) {
        if (sessionKey != null) {
            stops.remove(sessionKey);
        }
    }

    /**
     * 下发停止信号。
     *
     * @return true 表示命中并执行了取消动作；false 表示没有正在运行的会话
     */
    public boolean cancel(String sessionKey) {
        Runnable stop = sessionKey == null ? null : stops.remove(sessionKey);
        if (stop == null) {
            return false;
        }
        stop.run();
        return true;
    }
}
