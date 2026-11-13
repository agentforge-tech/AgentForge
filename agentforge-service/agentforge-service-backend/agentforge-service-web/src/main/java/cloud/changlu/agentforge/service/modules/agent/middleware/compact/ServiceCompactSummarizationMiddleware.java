package cloud.changlu.agentforge.service.modules.agent.middleware.compact;

import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.extend.middlewares.compact.CompactSummarizationMiddleware;
import cloud.changlu.agentforge.agent.extend.middlewares.compact.domain.CompactEvent;
import cloud.changlu.agentforge.agent.extend.middlewares.compact.domain.CompactTrigger;
import cloud.changlu.agentforge.service.modules.agent.middleware.ServiceStreamingMiddleware;
import cloud.changlu.agentforge.service.modules.agent.middleware.SessionLifecycleMiddleware;
import cloud.changlu.agentforge.service.protocol.CompactResult;
import cloud.changlu.agentforge.service.protocol.StreamEventResponse;
import cloud.changlu.agentforge.service.protocol.processor.compact.AiCompactProcessor;

/**
 * 将 Core 压缩生命周期钩子适配为 Service SSE 事件。
 *
 * <p>调试模式输出完整压缩指标（环节、触发来源、前后消息与 Token、耗时等）； 发布模式只输出压缩开始、完成状态与耗时，保持精简。
 */
public class ServiceCompactSummarizationMiddleware extends CompactSummarizationMiddleware {

    private final ServiceStreamingMiddleware streamingMiddleware;
    private final SessionLifecycleMiddleware sessionLifecycleMiddleware;
    private final boolean detailed;

    public ServiceCompactSummarizationMiddleware(
            CompactConfig config, ServiceStreamingMiddleware streamingMiddleware) {
        this(config, streamingMiddleware, null, false);
    }

    public ServiceCompactSummarizationMiddleware(
            CompactConfig config,
            ServiceStreamingMiddleware streamingMiddleware,
            boolean detailed) {
        this(config, streamingMiddleware, null, detailed);
    }

    public ServiceCompactSummarizationMiddleware(
            CompactConfig config,
            ServiceStreamingMiddleware streamingMiddleware,
            SessionLifecycleMiddleware sessionLifecycleMiddleware,
            boolean detailed) {
        super(config);
        if (streamingMiddleware == null) {
            throw new IllegalArgumentException("streamingMiddleware must not be null");
        }
        this.streamingMiddleware = streamingMiddleware;
        this.sessionLifecycleMiddleware = sessionLifecycleMiddleware;
        this.detailed = detailed;
    }

    @Override
    public void beforeCompact(
            int currentStep,
            CompactTrigger trigger,
            int beforeMessages,
            int beforeTokens,
            int maxContextTokens,
            AgentChatContext chatContext) {
        CompactResult result = new CompactResult();
        result.setStatus("compacting");
        result.setTrigger(trigger == null ? null : trigger.getCode());
        if (detailed) {
            result.setCurrentStep(currentStep);
            result.setBeforeMessages(beforeMessages);
            result.setBeforeTokens(beforeTokens);
            result.setMaxContextTokens(maxContextTokens);
            result.setBeforeUsagePercent(usagePercent(beforeTokens, maxContextTokens));
            result.setTriggerDescription(trigger == null ? null : trigger.getDescription());
        }
        publish(chatContext, AiCompactProcessor.compacting(result));
    }

    @Override
    public void afterCompact(CompactEvent event, AgentChatContext chatContext) {
        if (event == null) {
            return;
        }
        CompactResult result = new CompactResult();
        result.setStatus(event.isSuccess() ? "compacted" : "compact_failed");
        result.setSuccess(event.isSuccess());
        result.setDuration(event.getDurationMs());
        result.setTrigger(event.getTrigger() == null ? null : event.getTrigger().getCode());
        if (detailed) {
            result.setCurrentStep(event.getStep());
            result.setBeforeMessages(event.getBeforeMessages());
            result.setAfterMessages(event.getAfterMessages());
            result.setBeforeTokens(event.getBeforeTokens());
            result.setAfterTokens(event.getAfterTokens());
            result.setMaxContextTokens(event.getMaxContextTokens());
            result.setBeforeUsagePercent(
                    usagePercent(event.getBeforeTokens(), event.getMaxContextTokens()));
            result.setAfterUsagePercent(
                    usagePercent(event.getAfterTokens(), event.getMaxContextTokens()));
            result.setSavedTokens(event.getBeforeTokens() - event.getAfterTokens());
            result.setSavedPercent(
                    event.getBeforeTokens() <= 0
                            ? 0D
                            : (double) (event.getBeforeTokens() - event.getAfterTokens())
                                    / event.getBeforeTokens()
                                    * 100D);
            result.setTriggerDescription(
                    event.getTrigger() == null ? null : event.getTrigger().getDescription());
        }
        if (!event.isSuccess()) {
            result.setErrorMessage(event.getErrorMessage());
        }
        publish(
                chatContext,
                event.isSuccess()
                        ? AiCompactProcessor.compacted(result)
                        : AiCompactProcessor.failed(result));
    }

    private void publish(AgentChatContext context, StreamEventResponse response) {
        Object memoryId = context == null ? null : context.getMemoryId();
        streamingMiddleware.publish(memoryId, response);
        if (sessionLifecycleMiddleware != null) {
            sessionLifecycleMiddleware.collectEvent(memoryId, response);
        }
    }

    private static Double usagePercent(int tokens, int maxContextTokens) {
        return maxContextTokens <= 0 ? null : (double) tokens / maxContextTokens * 100D;
    }
}
