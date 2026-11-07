package cloud.changlu.agentforge.service.modules.agent.service.impl;

import cloud.changlu.agentforge.service.domain.req.ChatStreamRequest;
import cloud.changlu.agentforge.service.env.EnvironmentContext;
import cloud.changlu.agentforge.service.modules.agent.domain.config.AgentRuntimeConfig;
import cloud.changlu.agentforge.service.modules.agent.domain.req.AgentChatReq;
import cloud.changlu.agentforge.service.modules.agent.domain.vo.AgentChatTaskStopVo;
import cloud.changlu.agentforge.service.modules.agent.domain.vo.ChatTaskSubmitVo;
import cloud.changlu.agentforge.service.modules.agent.event.store.MessageEventFactory;
import cloud.changlu.agentforge.service.modules.agent.middleware.ServiceStreamingMiddleware;
import cloud.changlu.agentforge.service.modules.agent.middleware.SessionLifecycleMiddleware;
import cloud.changlu.agentforge.service.modules.agent.runtime.base.IAgentManager;
import cloud.changlu.agentforge.service.modules.agent.runtime.context.AgentInitContext;
import cloud.changlu.agentforge.service.modules.agent.runtime.factory.AgentManagerFactory;
import cloud.changlu.agentforge.service.modules.agent.service.AgentChatTaskService;
import cloud.changlu.agentforge.service.modules.session.dao.AgentSessionMapper;
import cloud.changlu.agentforge.service.modules.session.dao.AgentSessionMessageMapper;
import cloud.changlu.agentforge.service.modules.session.domain.AgentSessionMessagePojo;
import cloud.changlu.agentforge.service.modules.session.domain.AgentSessionPojo;
import cloud.changlu.agentforge.service.modules.session.enums.SessionMessageRoleEnum;
import cloud.changlu.agentforge.service.modules.session.enums.SessionMessageStatusEnum;
import cloud.changlu.agentforge.service.modules.session.enums.SessionStatusEnum;
import cloud.changlu.agentforge.service.modules.session.enums.SessionStatusTransfer;
import cloud.changlu.agentforge.service.modules.session.service.IAgentSessionService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

@Service
public class AgentChatTaskServiceImpl implements AgentChatTaskService {
    private final AgentRuntimeConfigResolver runtimeConfigResolver;
    private final AgentManagerFactory agentManagerFactory;
    private final ServiceStreamingMiddleware streamingMiddleware;
    private final MessageEventFactory eventFactory;
    private final SessionLifecycleMiddleware lifecycleMiddleware;
    private final IAgentSessionService sessionService;
    private final MessageEventSnapshotService snapshotService;
    private final AgentSessionMessageMapper agentSessionMessageMapper;
    private final AgentSessionMapper agentSessionMapper;
    private final EnvironmentContext environmentContext;
    private final Executor taskExecutor;
    private final Map<Long, Runnable> activeStops = new ConcurrentHashMap<Long, Runnable>();

    public AgentChatTaskServiceImpl(
            AgentRuntimeConfigResolver runtimeConfigResolver,
            AgentManagerFactory agentManagerFactory,
            ServiceStreamingMiddleware streamingMiddleware,
            MessageEventFactory eventFactory,
            SessionLifecycleMiddleware lifecycleMiddleware,
            IAgentSessionService sessionService,
            MessageEventSnapshotService snapshotService,
            AgentSessionMessageMapper agentSessionMessageMapper,
            AgentSessionMapper agentSessionMapper,
            EnvironmentContext environmentContext,
            @Qualifier("chatTaskExecutor") Executor taskExecutor) {
        this.runtimeConfigResolver = runtimeConfigResolver;
        this.agentManagerFactory = agentManagerFactory;
        this.streamingMiddleware = streamingMiddleware;
        this.eventFactory = eventFactory;
        this.lifecycleMiddleware = lifecycleMiddleware;
        this.sessionService = sessionService;
        this.snapshotService = snapshotService;
        this.agentSessionMessageMapper = agentSessionMessageMapper;
        this.agentSessionMapper = agentSessionMapper;
        this.environmentContext = environmentContext;
        this.taskExecutor = taskExecutor;
    }

    @Override
    public ChatTaskSubmitVo submit(ChatStreamRequest body, Long userId) {
        validate(body);
        boolean debug = Boolean.TRUE.equals(body.getIsDebug());
        AgentRuntimeConfig config = runtimeConfigResolver.resolve(body.getAid(), debug);
        IAgentSessionService.SessionInitResult init =
                sessionService.initChat(
                        config.getAid(),
                        body.getSessionId(),
                        body.getMessage().trim(),
                        userId,
                        debug);
        IAgentManager<Object, Object> manager =
                agentManagerFactory.getAgentManagerByType(config.getType());
        Object agent =
                manager.getAgentAndInitAgent(
                        AgentInitContext.builder()
                                .aid(config.getAid())
                                .agentName(config.getName())
                                .agentConfig(config.getAgentConfig())
                                .debug(debug)
                                .channelId(AgentInitContext.WEBSITE_CHANNEL_ID)
                                .build());
        AgentChatReq request = buildRequest(config, body, init, debug);
        request.setExecutionId(manager.createExecutionId(agent));
        registerRun(init, manager, agent, request.getExecutionId());
        try {
            taskExecutor.execute(() -> start(manager, agent, request, init.getAiMessageId()));
        } catch (RuntimeException e) {
            activeStops.remove(init.getAiMessageId());
            streamingMiddleware.onError(init.getSessionId(), e);
            lifecycleMiddleware.finishWithError(init.getSessionId(), e);
            throw e;
        }
        return toSubmitVo(init);
    }

    @Override
    public AgentChatTaskStopVo stop(Long messageId, Long userId) {
        // 步骤 1：校验 AI 消息归属。
        AgentSessionMessagePojo message = requireOwnedAiMessage(messageId, userId);

        // 步骤 2：已终态直接返回，保证接口幂等。
        SessionMessageStatusEnum current = SessionMessageStatusEnum.getByCode(message.getStatus());
        if (SessionStatusTransfer.isTerminal(current)) {
            return buildStopVo(messageId, false, current, false);
        }

        // 步骤 3：先把消息置为 CANCELLING(7)，作为取消意图的落库状态。
        // CAS 成功说明本次请求是第一个受理者，由它下发取消；失败说明已有其他请求在取消中或已终态。
        int marked =
                agentSessionMessageMapper.markCancelling(
                        messageId, SessionMessageStatusEnum.CANCELLING.getCode(), new Date());
        boolean firstCanceller = marked > 0;

        // 步骤 4：由第一个受理者下发本机取消信号，并把会话标记为取消中。
        // 未命中不代表失败（可能任务已完成或运行在其他实例）。
        boolean cancelRequested = false;
        if (firstCanceller) {
            agentSessionMapper.updateStatus(
                    message.getSessionId(), SessionStatusEnum.CANCELLING.getCode(), new Date());
            Runnable stop = activeStops.remove(messageId);
            if (stop != null) {
                stop.run();
                cancelRequested = true;
            }
        }

        // 步骤 5：轮询等待消息进入终态。
        SessionMessageStatusEnum terminal = awaitTerminal(messageId);
        if (terminal != null) {
            return buildStopVo(messageId, cancelRequested, terminal, false);
        }

        // 步骤 6：等待超时，CAS 兜底收口为 TIMEOUT_CANCELLED。
        return timeoutCancel(messageId, cancelRequested);
    }

    @Override
    public AgentChatTaskStopVo stopBySession(String sessionId, Long userId) {
        // 一段式场景：先解析该会话当前运行中的 AI 消息，再复用基于消息的停止与等待逻辑。
        Long messageId = findActiveAiMessageId(sessionId);
        if (messageId == null) {
            AgentChatTaskStopVo vo = new AgentChatTaskStopVo();
            vo.setCancelRequested(false);
            return vo;
        }
        return stop(messageId, userId);
    }

    /** 找到指定业务会话下最近一条仍可取消的 AI 消息。 */
    private Long findActiveAiMessageId(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return null;
        }
        AgentSessionPojo session = agentSessionMapper.selectBySessionId(sessionId);
        if (session == null || session.getId() == null) {
            return null;
        }
        List<AgentSessionMessagePojo> messages =
                agentSessionMessageMapper.selectBySessionId(session.getId());
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            AgentSessionMessagePojo message = messages.get(i);
            if (!SessionMessageRoleEnum.AI.getCode().equals(message.getRoleType())) {
                continue;
            }
            if (isCancelable(message.getStatus())) {
                return message.getId();
            }
        }
        return null;
    }

    private static boolean isCancelable(Integer status) {
        return status != null
                && (status.equals(SessionMessageStatusEnum.CREATED.getCode())
                        || status.equals(SessionMessageStatusEnum.RUNNING.getCode())
                        || status.equals(SessionMessageStatusEnum.CANCELLING.getCode()));
    }

    /** 轮询等待消息进入终态；超时或线程中断返回 null。 */
    private SessionMessageStatusEnum awaitTerminal(Long messageId) {
        long timeoutMs = environmentContext.getChatCancelWaitTimeoutMs();
        long pollMs = environmentContext.getChatCancelPollIntervalMs();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            AgentSessionMessagePojo current = agentSessionMessageMapper.selectById(messageId);
            if (current == null) {
                return null;
            }
            SessionMessageStatusEnum status =
                    SessionMessageStatusEnum.getByCode(current.getStatus());
            if (SessionStatusTransfer.isTerminal(status)) {
                return status;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(pollMs));
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
        }
        return null;
    }

    /** 超时兜底：仅从 CREATED/RUNNING 条件更新为 TIMEOUT_CANCELLED，失败则重读真实终态。 */
    private AgentChatTaskStopVo timeoutCancel(Long messageId, boolean cancelRequested) {
        Date now = new Date();
        AgentSessionMessagePojo message = agentSessionMessageMapper.selectById(messageId);
        if (message == null) {
            return buildStopVo(messageId, cancelRequested, null, false);
        }
        long startMillis =
                message.getStartedAt() != null
                        ? message.getStartedAt().getTime()
                        : (message.getCreateTime() != null
                                ? message.getCreateTime().getTime()
                                : now.getTime());
        long elapsedMs = Math.max(0L, now.getTime() - startMillis);
        int updated =
                agentSessionMessageMapper.timeoutCancelIfRunning(
                        messageId,
                        SessionMessageStatusEnum.TIMEOUT_CANCELLED.getCode(),
                        elapsedMs,
                        now,
                        now);
        if (updated > 0) {
            agentSessionMapper.updateStatus(
                    message.getSessionId(), SessionStatusEnum.FINISHED.getCode(), now);
            return buildStopVo(
                    messageId, cancelRequested, SessionMessageStatusEnum.TIMEOUT_CANCELLED, true);
        }
        AgentSessionMessagePojo latest = agentSessionMessageMapper.selectById(messageId);
        SessionMessageStatusEnum winner =
                latest == null ? null : SessionMessageStatusEnum.getByCode(latest.getStatus());
        return buildStopVo(messageId, cancelRequested, winner, false);
    }

    private AgentSessionMessagePojo requireOwnedAiMessage(Long messageId, Long userId) {
        if (messageId == null) {
            throw new IllegalArgumentException("messageId 不能为空");
        }
        AgentSessionMessagePojo message = agentSessionMessageMapper.selectById(messageId);
        if (message == null || !SessionMessageRoleEnum.AI.getCode().equals(message.getRoleType())) {
            throw new IllegalArgumentException("AI消息不存在或已被删除");
        }
        sessionService.requireOwned(message.getSessionId(), userId);
        return message;
    }

    private static AgentChatTaskStopVo buildStopVo(
            Long messageId,
            boolean cancelRequested,
            SessionMessageStatusEnum status,
            boolean timedOut) {
        AgentChatTaskStopVo vo = new AgentChatTaskStopVo();
        vo.setMessageId(messageId);
        vo.setCancelRequested(cancelRequested);
        vo.setStatus(status == null ? null : status.getCode());
        vo.setStatusName(status == null ? null : status.name());
        vo.setTimedOut(timedOut);
        return vo;
    }

    private void registerRun(
            IAgentSessionService.SessionInitResult init,
            IAgentManager<Object, Object> manager,
            Object agent,
            String executionId) {
        streamingMiddleware.bind(
                init.getSessionId(), eventFactory.createSink(init.getAiMessageId()));
        lifecycleMiddleware.registerRun(
                init.getSessionId(),
                init.getSessionId(),
                init.getSessionPk(),
                init.getAiMessageId());
        activeStops.put(
                init.getAiMessageId(),
                () -> manager.cancel(agent, init.getSessionId(), executionId));
    }

    private void start(
            IAgentManager<Object, Object> manager,
            Object agent,
            AgentChatReq request,
            long messageId) {
        try {
            manager.stream(agent, request)
                    .onCompleteResponse(response -> finishTask(messageId))
                    .onError(error -> finishTask(messageId))
                    .ignoreErrors()
                    .start();
        } catch (RuntimeException e) {
            streamingMiddleware.onError(request.getSessionId(), e);
            lifecycleMiddleware.finishWithError(request.getSessionId(), e);
            finishTask(messageId);
        }
    }

    private void finishTask(long messageId) {
        activeStops.remove(messageId);
        try {
            snapshotService.saveSnapshot(messageId);
        } catch (RuntimeException ignored) {
            // stream 订阅端会在观察到终态后自愈快照。
        }
    }

    private static AgentChatReq buildRequest(
            AgentRuntimeConfig config,
            ChatStreamRequest body,
            IAgentSessionService.SessionInitResult init,
            boolean debug) {
        AgentChatReq request = new AgentChatReq();
        request.setAid(config.getAid());
        request.setSessionId(init.getSessionId());
        request.setMessage(body.getMessage().trim());
        request.setIsDebug(debug);
        request.setAiMessageId(init.getAiMessageId());
        request.setSessionPk(init.getSessionPk());
        request.setSessionTitle(init.getTitle());
        return request;
    }

    private static ChatTaskSubmitVo toSubmitVo(IAgentSessionService.SessionInitResult init) {
        ChatTaskSubmitVo result = new ChatTaskSubmitVo();
        result.setSessionId(init.getSessionId());
        result.setSessionPk(init.getSessionPk());
        result.setUserMessageId(init.getUserMessageId());
        result.setMessageId(init.getAiMessageId());
        result.setStatus(SessionMessageStatusEnum.CREATED.getCode());
        result.setTitle(init.getTitle());
        return result;
    }

    private static void validate(ChatStreamRequest body) {
        if (body == null || body.getAid() == null || body.getAid().trim().isEmpty()) {
            throw new IllegalArgumentException("aid 不能为空");
        }
        if (body.getMessage() == null || body.getMessage().trim().isEmpty()) {
            throw new IllegalArgumentException("对话内容不能为空");
        }
    }
}
