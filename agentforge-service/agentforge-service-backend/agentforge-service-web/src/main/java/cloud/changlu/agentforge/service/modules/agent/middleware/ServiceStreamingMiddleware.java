package cloud.changlu.agentforge.service.modules.agent.middleware;

import cloud.changlu.agentforge.agent.component.middleware.IStreamingIAgentMiddleware;
import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.domain.StopResult;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.AgentSuspendInfo;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptResumeContext;
import cloud.changlu.agentforge.agent.stream.PartialThinking;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.model.chat.request.ChatRequest;
import cloud.changlu.agentforge.model.chat.response.ChatResponse;
import cloud.changlu.agentforge.service.modules.agent.event.sink.SseEmitterEventSink;
import cloud.changlu.agentforge.service.modules.agent.event.sink.StreamEventSink;
import cloud.changlu.agentforge.service.modules.agent.interrupt.AgentInterruptRuntimeRegistry;
import cloud.changlu.agentforge.service.protocol.StreamEventResponse;
import cloud.changlu.agentforge.service.protocol.ToolCallResult;
import cloud.changlu.agentforge.service.protocol.processor.AiErrorProcessor;
import cloud.changlu.agentforge.service.protocol.processor.AiResponseProcessor;
import cloud.changlu.agentforge.service.protocol.processor.AiSessionProcessor;
import cloud.changlu.agentforge.service.protocol.processor.interrupt.InterruptEventProcessor;
import cloud.changlu.agentforge.service.protocol.processor.retry.AiRetryProcessor;
import cloud.changlu.agentforge.service.protocol.processor.think.AiThinkProcessor;
import cloud.changlu.agentforge.service.protocol.processor.tool.AiToolCallProcessor;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service 统一流式中间件：实现 {@link IStreamingIAgentMiddleware}，由 Agent 在流式执行各阶段回调驱动， 各事件场景委托给对应 processor
 * 构造 {@link StreamEventResponse} 后写出到当前会话的 SSE。
 */
public class ServiceStreamingMiddleware implements IStreamingIAgentMiddleware {

    private final AgentInterruptRuntimeRegistry interruptRegistry;
    private final SessionLifecycleMiddleware lifecycleMiddleware;

    public ServiceStreamingMiddleware(
            AgentInterruptRuntimeRegistry interruptRegistry,
            SessionLifecycleMiddleware lifecycleMiddleware) {
        this.interruptRegistry = interruptRegistry;
        this.lifecycleMiddleware = lifecycleMiddleware;
    }

    /** memoryId -> 本次运行的会话输出上下文 */
    private final Map<Object, SessionContext> sessions =
            new ConcurrentHashMap<Object, SessionContext>();

    /** 请求进入时绑定当前会话的 SSE 输出 */
    public void bind(Object memoryId, SseEmitter emitter) {
        bind(memoryId, new SseEmitterEventSink(emitter));
    }

    /** 绑定本轮事件出口；后台模式可绑定 MySQL/Redis Sink，不持有页面连接。 */
    public void bind(Object memoryId, StreamEventSink sink) {
        SessionContext previous = sessions.put(memoryId, new SessionContext(sink));
        if (previous != null) {
            previous.close();
        }
    }

    /** 请求结束时解绑会话输出 */
    public void unbind(Object memoryId) {
        SessionContext context = sessions.remove(memoryId);
        if (context != null) {
            context.dispose();
        }
    }

    /** 供 Agent 运行期扩展事件复用当前会话的串行 SSE 输出。 */
    public void publish(Object memoryId, StreamEventResponse event) {
        SessionContext context = memoryId == null ? null : sessions.get(memoryId);
        if (context != null && event != null) {
            context.send(event);
        }
    }

    /** 等待 HITL 时无 Agent 线程可取消，由 Service 直接取消批次并收口。 */
    public boolean cancelWaitingInterrupt(Object memoryId) {
        AgentInterruptRuntimeRegistry.BatchContext batch =
                interruptRegistry.cancelByMemoryId(memoryId);
        if (batch == null) {
            return false;
        }
        StreamEventResponse event = InterruptEventProcessor.cancelled(batch.eventContent());
        publish(memoryId, event);
        lifecycleMiddleware.cancelWaitingForHitl(memoryId, event);
        unbind(memoryId);
        interruptRegistry.release(memoryId);
        return true;
    }

    /** 模型调用重试：把“第几轮、第几次、等待秒数”推给前端。 */
    @Override
    public void onAiCallRetry(
            int currentStep,
            ChatRequest chatRequest,
            AgentChatContext chatContext,
            int retryCount,
            int maxRetries,
            long delayMs,
            Exception lastException) {
        SessionContext context = state(chatContext);
        if (context != null) {
            context.send(AiRetryProcessor.retry(currentStep, retryCount, maxRetries, delayMs));
        }
    }

    /** 权限审核 / AskUser 中断被冻结时调用。 */
    @Override
    public void onInterruptRequested(
            int currentStep, AgentSuspendInfo suspendInfo, AgentChatContext chatContext) {
        if (suspendInfo == null || chatContext == null) {
            return;
        }
        AgentInterruptRuntimeRegistry.BatchContext batch =
                interruptRegistry.suspend(chatContext.getMemoryId(), suspendInfo);
        StreamEventResponse event = InterruptEventProcessor.requested(batch.eventContent());
        publish(chatContext.getMemoryId(), event);
        lifecycleMiddleware.markWaitingForHitl(chatContext.getMemoryId(), event);
    }

    /** 冻结批次开始恢复前调用。 */
    @Override
    public void onBeforeInterruptResume(
            InterruptResumeContext resumeContext, AgentChatContext chatContext) {
        if (resumeContext == null || chatContext == null) {
            return;
        }
        AgentInterruptRuntimeRegistry.BatchContext batch =
                interruptRegistry.requireBatch(resumeContext.getBatchId());
        StreamEventResponse event = InterruptEventProcessor.resuming(batch.eventContent());
        publish(chatContext.getMemoryId(), event);
        lifecycleMiddleware.markResuming(chatContext.getMemoryId(), event);
    }

    /** 冻结工具结果已写回记忆后调用。 */
    @Override
    public void onAfterInterruptResume(
            InterruptResumeContext resumeContext, AgentChatContext chatContext) {
        if (resumeContext == null || chatContext == null) {
            return;
        }
        interruptRegistry.completed(resumeContext.getBatchId());
        AgentInterruptRuntimeRegistry.BatchContext batch =
                interruptRegistry.requireBatch(resumeContext.getBatchId());
        StreamEventResponse event = InterruptEventProcessor.resumed(batch.eventContent());
        publish(chatContext.getMemoryId(), event);
        lifecycleMiddleware.collectEvent(chatContext.getMemoryId(), event);
    }

    /** 中断恢复或冻结工具执行失败时调用。 */
    @Override
    public void onInterruptResumeError(
            InterruptResumeContext resumeContext, Throwable error, AgentChatContext chatContext) {
        if (resumeContext == null || chatContext == null) {
            return;
        }
        interruptRegistry.failed(resumeContext.getBatchId());
        AgentInterruptRuntimeRegistry.BatchContext batch =
                interruptRegistry.requireBatch(resumeContext.getBatchId());
        Map<String, Object> content = new java.util.LinkedHashMap<>(batch.eventContent());
        content.put("error", safeMessage(error));
        StreamEventResponse event = InterruptEventProcessor.failed(content);
        publish(chatContext.getMemoryId(), event);
        lifecycleMiddleware.collectEvent(chatContext.getMemoryId(), event);
    }

    /** 同步启动异常兜底：把错误推给前端并解绑 */
    public void onError(Object memoryId, Throwable error) {
        SessionContext context = sessions.get(memoryId);
        if (context != null) {
            context.send(AiErrorProcessor.respError(safeMessage(error)));
        }
        unbind(memoryId);
    }

    @Override
    public void onInitComplete(AgentChatContext chatContext) {
        SessionContext context = state(chatContext);
        if (context != null) {
            context.send(
                    AiSessionProcessor.sessionCreated(String.valueOf(chatContext.getMemoryId())));
        }
    }

    @Override
    public void onPartialThinking(
            int currentStep, PartialThinking partialThinking, AgentChatContext chatContext) {
        SessionContext context = state(chatContext);
        if (context == null || partialThinking == null || partialThinking.text() == null) {
            return;
        }
        context.send(AiThinkProcessor.respThink(partialThinking.text(), false));
    }

    @Override
    public void onPartialResponse(
            int currentStep, String partialResponse, AgentChatContext chatContext) {
        SessionContext context = state(chatContext);
        if (context == null || partialResponse == null) {
            return;
        }
        context.send(AiResponseProcessor.respText(partialResponse, false));
    }

    @Override
    public void beforeToolExecution(
            ToolExecutionRequest toolRequest, AgentChatContext chatContext) {
        SessionContext context = state(chatContext);
        if (context != null && toolRequest != null) {
            context.startTool(toolRequest);
        }
    }

    @Override
    public String afterToolExecution(
            ToolExecutionRequest toolRequest, String toolResult, AgentChatContext chatContext) {
        SessionContext context = state(chatContext);
        if (context != null) {
            context.sendTool(toolRequest, toolResult, false);
        }
        return toolResult;
    }

    @Override
    public void onToolExecutionError(
            ToolExecutionRequest toolRequest, Throwable error, AgentChatContext chatContext) {
        SessionContext context = state(chatContext);
        if (context != null) {
            context.sendTool(toolRequest, safeMessage(error), true);
        }
    }

    @Override
    public ChatResponse afterModelCall(
            int currentStep,
            ChatRequest chatRequest,
            ChatResponse chatResponse,
            AgentChatContext chatContext) {
        System.out.println(chatResponse);
        return IStreamingIAgentMiddleware.super.afterModelCall(
                currentStep, chatRequest, chatResponse, chatContext);
    }

    @Override
    public void beforeConcurrentToolExecution(
            List<ToolExecutionRequest> toolRequests, AgentChatContext chatContext) {
        SessionContext context = state(chatContext);
        if (context == null || toolRequests == null) {
            return;
        }
        for (ToolExecutionRequest toolRequest : toolRequests) {
            context.startTool(toolRequest);
        }
    }

    @Override
    public void onConcurrentToolExecutionComplete(
            ToolExecutionRequest toolRequest,
            String toolResult,
            Throwable error,
            AgentChatContext chatContext) {
        // 每个工具在自己的完成时刻单独发出完成事件（携带各自耗时）；失败项已由 onToolExecutionError 发出
        if (error != null) {
            return;
        }
        SessionContext context = state(chatContext);
        if (context != null && toolRequest != null) {
            context.sendTool(toolRequest, toolResult, false);
        }
    }

    @Override
    public List<String> afterConcurrentToolExecution(
            List<ToolExecutionRequest> toolRequests,
            List<String> toolResults,
            AgentChatContext chatContext) {
        // 完成事件已在 onConcurrentToolExecutionComplete 中逐工具发出，这里仅透传结果
        return toolResults;
    }

    @Override
    public void onStop(int currentStep, StopResult stopResult, AgentChatContext chatContext) {
        SessionContext context = state(chatContext);
        if (context != null) {
            context.send(AiResponseProcessor.respEnd(true));
        }
        Object memoryId = chatContext == null ? null : chatContext.getMemoryId();
        unbind(memoryId);
        interruptRegistry.release(memoryId);
    }

    @Override
    public void onStopWithError(int currentStep, Throwable error, AgentChatContext chatContext) {
        SessionContext context = state(chatContext);
        if (context != null) {
            context.send(AiErrorProcessor.respError(safeMessage(error)));
        }
        Object memoryId = chatContext == null ? null : chatContext.getMemoryId();
        unbind(memoryId);
        interruptRegistry.release(memoryId);
    }

    private SessionContext state(AgentChatContext chatContext) {
        return chatContext == null ? null : sessions.get(chatContext.getMemoryId());
    }

    private static String safeMessage(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    /** 单次运行的 SSE 输出上下文，内部维护关闭状态与工具耗时 */
    private static final class SessionContext {

        private final StreamEventSink sink;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final Map<String, Long> toolStartTimes = new ConcurrentHashMap<String, Long>();

        private SessionContext(StreamEventSink sink) {
            this.sink = sink;
        }

        private void startTool(ToolExecutionRequest request) {
            if (request.id() != null) {
                toolStartTimes.put(request.id(), System.currentTimeMillis());
            }
            ToolCallResult toolCallResult = new ToolCallResult();
            toolCallResult.setToolCallId(request.id());
            toolCallResult.setToolName(request.name());
            toolCallResult.setInputParams(request.arguments());
            toolCallResult.setDescription("工具执行中");
            send(AiToolCallProcessor.toolCallCard(toolCallResult, false));
        }

        private void sendTool(ToolExecutionRequest request, String result, boolean failed) {
            long duration = 0L;
            if (request != null && request.id() != null) {
                Long start = toolStartTimes.remove(request.id());
                if (start != null) {
                    duration = System.currentTimeMillis() - start;
                }
            }
            ToolCallResult toolCallResult = new ToolCallResult();
            if (request != null) {
                toolCallResult.setToolCallId(request.id());
                toolCallResult.setToolName(request.name());
                toolCallResult.setInputParams(request.arguments());
            }
            toolCallResult.setDescription(failed ? "工具执行失败" : "工具执行完成");
            toolCallResult.setOutputResult(result);
            toolCallResult.setDuration(duration);
            send(AiToolCallProcessor.toolCallCard(toolCallResult, true));
        }

        private synchronized void send(StreamEventResponse event) {
            if (closed.get()) {
                return;
            }
            sink.publish(event);
        }

        private void close() {
            closed.set(true);
            sink.close();
        }

        private void dispose() {
            if (closed.compareAndSet(false, true)) {
                sink.close();
            }
        }
    }
}
