package cloud.changlu.agentforge.service.modules.agent.middleware;

import cloud.changlu.agentforge.agent.component.middleware.IStreamingIAgentMiddleware;
import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.domain.StopResult;
import cloud.changlu.agentforge.agent.stream.PartialThinking;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.service.modules.session.dao.AgentSessionMapper;
import cloud.changlu.agentforge.service.modules.session.dao.AgentSessionMessageMapper;
import cloud.changlu.agentforge.service.modules.session.enums.SessionMessageStatusEnum;
import cloud.changlu.agentforge.service.modules.session.enums.SessionStatusEnum;
import cloud.changlu.agentforge.service.modules.session.enums.SessionStatusTransfer;
import cloud.changlu.agentforge.service.protocol.StreamEventResponse;
import cloud.changlu.agentforge.service.protocol.ToolCallResult;
import cloud.changlu.agentforge.service.protocol.processor.AiResponseProcessor;
import cloud.changlu.agentforge.service.protocol.processor.think.AiThinkProcessor;
import cloud.changlu.agentforge.service.protocol.processor.tool.AiToolCallProcessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话生命周期中间件：唯一负责 AI 消息创建后的状态推进、事件收集与一次性收口。
 *
 * <p>会话与 USER/AI 占位消息在请求初始化阶段完成；这里的职责边界是：
 *
 * <ul>
 *   <li>{@code onInitComplete}：AI 消息 1 → 2，写 started_at，会话 0/2/3 → 1
 *   <li>流式期间：事件只在内存聚合，不写库
 *   <li>{@code onStop}/{@code onStopWithError}：一次性写 content/events/elapsed_ms/终态，会话回 2
 * </ul>
 */
public class SessionLifecycleMiddleware implements IStreamingIAgentMiddleware {

    private static final Logger log = LoggerFactory.getLogger(SessionLifecycleMiddleware.class);

    /** 会话会话标识（等于 memoryId），由请求初始化写入运行期上下文。 */
    public static final String EXT_SESSION_ID = "session.sessionId";

    /** AI 消息主键。 */
    public static final String EXT_AI_MESSAGE_ID = "session.aiMessageId";

    /** 本轮 AI 消息顺序号。 */
    public static final String EXT_SEQ_NUM = "session.seqNum";

    private final AgentSessionMapper agentSessionMapper;
    private final AgentSessionMessageMapper agentSessionMessageMapper;
    private final ObjectMapper objectMapper;

    private final Map<Object, RunState> states = new ConcurrentHashMap<>();

    public SessionLifecycleMiddleware(
            AgentSessionMapper agentSessionMapper,
            AgentSessionMessageMapper agentSessionMessageMapper,
            ObjectMapper objectMapper) {
        this.agentSessionMapper = agentSessionMapper;
        this.agentSessionMessageMapper = agentSessionMessageMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onInitComplete(AgentChatContext chatContext) {
        RunState state = state(chatContext);
        if (state == null) {
            return;
        }
        Date now = new Date();
        safeRun(
                "mark running",
                () -> {
                    agentSessionMessageMapper.updateStarted(
                            state.aiMessageId,
                            SessionMessageStatusEnum.RUNNING.getCode(),
                            now,
                            now);
                    agentSessionMapper.updateStatusBySessionId(
                            state.sessionId, SessionStatusEnum.RUNNING.getCode(), now);
                });
    }

    @Override
    public void onPartialThinking(
            int currentStep, PartialThinking partialThinking, AgentChatContext chatContext) {
        RunState state = state(chatContext);
        if (state == null || partialThinking == null || partialThinking.text() == null) {
            return;
        }
        state.collect(AiThinkProcessor.respThink(partialThinking.text(), false));
    }

    @Override
    public void onPartialResponse(
            int currentStep, String partialResponse, AgentChatContext chatContext) {
        RunState state = state(chatContext);
        if (state == null || partialResponse == null) {
            return;
        }
        state.content.append(partialResponse);
        state.collect(AiResponseProcessor.respText(partialResponse, false));
    }

    @Override
    public void beforeToolExecution(
            ToolExecutionRequest toolRequest, AgentChatContext chatContext) {
        RunState state = state(chatContext);
        if (state == null || toolRequest == null) {
            return;
        }
        state.toolStarted(toolRequest);
        ToolCallResult card = new ToolCallResult();
        card.setToolCallId(toolRequest.id());
        card.setToolName(toolRequest.name());
        card.setInputParams(toolRequest.arguments());
        card.setDescription("Tool execution in progress");
        state.collect(AiToolCallProcessor.toolCallCard(card, false));
    }

    @Override
    public String afterToolExecution(
            ToolExecutionRequest toolRequest, String toolResult, AgentChatContext chatContext) {
        RunState state = state(chatContext);
        if (state != null) {
            state.collectTool(toolRequest, toolResult, false);
        }
        return toolResult;
    }

    @Override
    public void onToolExecutionError(
            ToolExecutionRequest toolRequest, Throwable error, AgentChatContext chatContext) {
        RunState state = state(chatContext);
        if (state != null) {
            state.collectTool(toolRequest, message(error), true);
        }
    }

    @Override
    public void beforeConcurrentToolExecution(
            List<ToolExecutionRequest> toolRequests, AgentChatContext chatContext) {
        RunState state = state(chatContext);
        if (state == null || toolRequests == null) {
            return;
        }
        for (ToolExecutionRequest toolRequest : toolRequests) {
            state.toolStarted(toolRequest);
            ToolCallResult card = new ToolCallResult();
            card.setToolCallId(toolRequest.id());
            card.setToolName(toolRequest.name());
            card.setInputParams(toolRequest.arguments());
            card.setDescription("Tool execution in progress");
            state.collect(AiToolCallProcessor.toolCallCard(card, false));
        }
    }

    @Override
    public void onConcurrentToolExecutionComplete(
            ToolExecutionRequest toolRequest,
            String toolResult,
            Throwable error,
            AgentChatContext chatContext) {
        if (error != null) {
            return;
        }
        RunState state = state(chatContext);
        if (state != null && toolRequest != null) {
            state.collectTool(toolRequest, toolResult, false);
        }
    }

    @Override
    public void onStop(int currentStep, StopResult stopResult, AgentChatContext chatContext) {
        SessionMessageStatusEnum status =
                SessionStatusTransfer.fromStopResult(
                        stopResult == null ? null : stopResult.getStopResultState());
        String fallback = stopResult == null ? null : stopResult.getRunRes();
        finish(chatContext, status, fallback);
    }

    @Override
    public void onStopWithError(int currentStep, Throwable error, AgentChatContext chatContext) {
        finish(chatContext, SessionMessageStatusEnum.FAILED, message(error));
    }

    /** 异常兜底：Controller 启动失败时也能收口。 */
    public void finishWithError(Object memoryId, Throwable error) {
        RunState state = memoryId == null ? null : states.get(memoryId);
        if (state == null) {
            return;
        }
        finishState(state, SessionMessageStatusEnum.FAILED, message(error));
    }

    private void finish(
            AgentChatContext chatContext, SessionMessageStatusEnum status, String fallbackContent) {
        RunState state = state(chatContext);
        if (state == null) {
            return;
        }
        finishState(state, status, fallbackContent);
    }

    private void finishState(
            RunState state, SessionMessageStatusEnum status, String fallbackContent) {
        if (state == null || !state.finished.compareAndSet(false, true)) {
            return;
        }
        Date finishedAt = new Date();
        String content =
                state.content.length() > 0
                        ? state.content.toString()
                        : (fallbackContent == null ? state.content.toString() : fallbackContent);
        long elapsed = Math.max(0L, finishedAt.getTime() - state.startedAt.getTime());
        String eventsJson = toJson(state.events);
        try {
            agentSessionMessageMapper.finish(
                    state.aiMessageId,
                    status.getCode(),
                    content,
                    eventsJson,
                    elapsed,
                    finishedAt,
                    finishedAt);
            agentSessionMapper.updateStatus(
                    state.sessionPk, SessionStatusEnum.FINISHED.getCode(), finishedAt);
        } catch (RuntimeException e) {
            log.warn(
                    "会话收口写入失败, sessionId={}, aiMessageId={}",
                    state.sessionId,
                    state.aiMessageId,
                    e);
        } finally {
            states.remove(state.memoryId);
        }
    }

    private String toJson(List<StreamEventResponse> events) {
        if (events == null || events.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(events);
        } catch (Exception e) {
            log.warn("会话事件序列化失败, size={}", events.size(), e);
            return null;
        }
    }

    private RunState state(AgentChatContext chatContext) {
        if (chatContext == null) {
            return null;
        }
        Object memoryId = chatContext.getMemoryId();
        if (memoryId == null) {
            return null;
        }
        RunState existing = states.get(memoryId);
        if (existing != null) {
            return existing;
        }
        Object sessionId = chatContext.getExtension(EXT_SESSION_ID);
        Object aiMessageId = chatContext.getExtension(EXT_AI_MESSAGE_ID);
        if (sessionId == null || aiMessageId == null) {
            return null;
        }
        RunState created =
                new RunState(
                        memoryId,
                        String.valueOf(sessionId),
                        toLong(chatContext.getExtension("session.sessionPk")),
                        toLong(aiMessageId));
        RunState previous = states.putIfAbsent(memoryId, created);
        return previous == null ? created : previous;
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String message(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static void safeRun(String action, Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException e) {
            log.warn("会话中间件执行失败: {}", action, e);
        }
    }

    /** 单次运行状态。 */
    private static final class RunState {
        private final Object memoryId;
        private final String sessionId;
        private final Long sessionPk;
        private final Long aiMessageId;
        private final Date startedAt = new Date();
        private final StringBuilder content = new StringBuilder();
        private final List<StreamEventResponse> events = new ArrayList<>();
        private final Map<String, Long> toolStartTimes = new ConcurrentHashMap<>();
        private final java.util.concurrent.atomic.AtomicBoolean finished =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        private RunState(Object memoryId, String sessionId, Long sessionPk, Long aiMessageId) {
            this.memoryId = memoryId;
            this.sessionId = sessionId;
            this.sessionPk = sessionPk;
            this.aiMessageId = aiMessageId;
        }

        private synchronized void collect(StreamEventResponse event) {
            if (event != null) {
                events.add(event);
            }
        }

        private void toolStarted(ToolExecutionRequest request) {
            if (request.id() != null) {
                toolStartTimes.put(request.id(), System.currentTimeMillis());
            }
        }

        private void collectTool(ToolExecutionRequest request, String result, boolean failed) {
            long duration = 0L;
            if (request != null && request.id() != null) {
                Long start = toolStartTimes.remove(request.id());
                if (start != null) {
                    duration = System.currentTimeMillis() - start;
                }
            }
            ToolCallResult card = new ToolCallResult();
            if (request != null) {
                card.setToolCallId(request.id());
                card.setToolName(request.name());
                card.setInputParams(request.arguments());
            }
            card.setDescription(failed ? "Tool execution failed" : "Tool execution completed");
            card.setOutputResult(result);
            card.setDuration(duration);
            collect(AiToolCallProcessor.toolCallCard(card, true));
        }
    }
}
