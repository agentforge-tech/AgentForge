package cloud.changlu.agentforge.service.modules.agent.middleware.append;

import cloud.changlu.agentforge.agent.extend.middlewares.append.domain.AppendedUserMessage;
import cloud.changlu.agentforge.agent.extend.middlewares.append.event.UserMessageAppendEventListener;
import cloud.changlu.agentforge.service.modules.agent.middleware.ServiceStreamingMiddleware;
import cloud.changlu.agentforge.service.modules.agent.middleware.SessionLifecycleMiddleware;
import cloud.changlu.agentforge.service.protocol.StreamEventResponse;
import cloud.changlu.agentforge.service.protocol.processor.append.UserMessageAppendProcessor;

/** 将 Core 追加消息生命周期事件适配为 Service SSE 事件和会话事件历史。 */
public class ServiceUserMessageAppendEventListener implements UserMessageAppendEventListener {
    private final ServiceStreamingMiddleware streamingMiddleware;
    private final SessionLifecycleMiddleware lifecycleMiddleware;

    public ServiceUserMessageAppendEventListener(
            ServiceStreamingMiddleware streamingMiddleware,
            SessionLifecycleMiddleware lifecycleMiddleware) {
        this.streamingMiddleware = streamingMiddleware;
        this.lifecycleMiddleware = lifecycleMiddleware;
    }

    @Override
    public void queued(Object memoryId, AppendedUserMessage message, int queuePosition) {
        publish(memoryId, UserMessageAppendProcessor.queued(message, queuePosition));
    }

    @Override
    public void effective(Object memoryId, AppendedUserMessage message, int currentStep) {
        publish(memoryId, UserMessageAppendProcessor.effective(message, currentStep));
    }

    @Override
    public void deferred(Object memoryId, AppendedUserMessage message) {
        publish(memoryId, UserMessageAppendProcessor.deferred(message));
    }

    @Override
    public void canceled(Object memoryId, AppendedUserMessage message) {
        publish(memoryId, UserMessageAppendProcessor.canceled(message));
    }

    private void publish(Object memoryId, StreamEventResponse event) {
        streamingMiddleware.publish(memoryId, event);
        lifecycleMiddleware.collectEvent(memoryId, event);
    }
}
