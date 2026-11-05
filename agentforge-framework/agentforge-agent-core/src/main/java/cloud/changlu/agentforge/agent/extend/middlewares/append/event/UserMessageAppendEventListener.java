package cloud.changlu.agentforge.agent.extend.middlewares.append.event;

import cloud.changlu.agentforge.agent.extend.middlewares.append.domain.AppendedUserMessage;

/**
 * 追加消息生命周期事件监听器。
 *
 * <p>Core 只通知状态变化，由上层适配 SSE、WebSocket、持久化或其他交付方式。
 */
public interface UserMessageAppendEventListener {
    void queued(Object memoryId, AppendedUserMessage message, int queuePosition);

    void effective(Object memoryId, AppendedUserMessage message, int currentStep);

    void deferred(Object memoryId, AppendedUserMessage message);

    void canceled(Object memoryId, AppendedUserMessage message);
}
