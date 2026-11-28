package cloud.changlu.agentforge.service.modules.agent.recovery;

import cloud.changlu.agentforge.agent.IAgent;
import cloud.changlu.agentforge.agent.IResumableAgent;
import cloud.changlu.agentforge.agent.stream.TokenStream;
import cloud.changlu.agentforge.service.modules.agent.event.store.MessageEventFactory;
import cloud.changlu.agentforge.service.modules.agent.middleware.ServiceStreamingMiddleware;
import cloud.changlu.agentforge.service.modules.agent.middleware.SessionLifecycleMiddleware;
import cloud.changlu.agentforge.service.modules.agent.runtime.execute.CacheExecuteManager;
import cloud.changlu.agentforge.service.modules.agent.service.impl.MessageEventSnapshotService;
import cloud.changlu.agentforge.service.modules.session.dao.AgentSessionMapper;
import cloud.changlu.agentforge.service.modules.session.dao.AgentSessionMessageMapper;
import cloud.changlu.agentforge.service.modules.session.domain.AgentSessionMessagePojo;
import cloud.changlu.agentforge.service.modules.session.enums.SessionMessageStatusEnum;
import cloud.changlu.agentforge.service.modules.session.enums.SessionStatusEnum;
import cloud.changlu.agentforge.service.modules.session.enums.SessionStatusTransfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.concurrent.ExecutorService;

/**
 * 服务启动恢复的执行器：接管一轮"上次没跑完"的 Agent 任务。
 *
 * <p>职责与正常的任务提交完全一致——绑定事件出口、预注册运行状态、绑定取消句柄、启动、收口， 差别只在于入口是 {@link IResumableAgent#resumeStream} 而不是
 * {@code runStream}， 并且不经过 {@code AgentChatTaskService}，因此现有对话链路一行不用改。
 */
@Component
public class AgentRecoveryExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentRecoveryExecutor.class);

    private final ServiceStreamingMiddleware streamingMiddleware;
    private final SessionLifecycleMiddleware lifecycleMiddleware;
    private final MessageEventFactory eventFactory;
    private final MessageEventSnapshotService snapshotService;
    private final AgentSessionMessageMapper agentSessionMessageMapper;
    private final AgentSessionMapper agentSessionMapper;
    private final CacheExecuteManager executeManager;
    private final ExecutorService taskExecutor;

    public AgentRecoveryExecutor(
            ServiceStreamingMiddleware streamingMiddleware,
            SessionLifecycleMiddleware lifecycleMiddleware,
            MessageEventFactory eventFactory,
            MessageEventSnapshotService snapshotService,
            AgentSessionMessageMapper agentSessionMessageMapper,
            AgentSessionMapper agentSessionMapper,
            CacheExecuteManager executeManager,
            @Qualifier("agentRecoveryTaskExecutor") ExecutorService taskExecutor) {
        this.streamingMiddleware = streamingMiddleware;
        this.lifecycleMiddleware = lifecycleMiddleware;
        this.eventFactory = eventFactory;
        this.snapshotService = snapshotService;
        this.agentSessionMessageMapper = agentSessionMessageMapper;
        this.agentSessionMapper = agentSessionMapper;
        this.executeManager = executeManager;
        this.taskExecutor = taskExecutor;
    }

    /**
     * 接管一轮恢复。
     *
     * @return true 表示已接管并提交执行；false 表示未接管（已在跑、或恢复准备阶段失败）
     */
    public boolean resume(AgentResumeTask task) {
        Object memoryId = task.getMemoryId();
        long messageId = task.getAiMessageId();

        // 步骤 1：准备恢复执行。resumeStream 会同步完成参数校验、读记忆、修消息链并生成新执行 ID，
        // 失败时直接抛异常，所以必须先拿到 TokenStream 才能登记取消句柄。
        TokenStream stream;
        try {
            stream =
                    ((IResumableAgent) task.getAgent())
                            .resumeStream(memoryId, task.getSourceExecutionId());
        } catch (RuntimeException e) {
            // 记忆为空 / 同会话已有活跃执行：这一轮无法续跑，但消息仍停在运行态，
            // 必须收口成失败，否则前端会一直转圈且下次启动还会重复命中同一条候选。
            log.warn("[AgentRecovery] 恢复准备失败 memoryId={}, messageId={}", memoryId, messageId, e);
            lifecycleMiddleware.registerRun(memoryId, task.getMemoryId(), messageId);
            streamingMiddleware.bind(memoryId, eventFactory.createSink(messageId));
            streamingMiddleware.onError(memoryId, e);
            lifecycleMiddleware.finishWithError(memoryId, e);
            finish(memoryId, messageId);
            return false;
        }

        // 步骤 2：登记运行态。新执行 ID 在 start() 之前就可读，因此取消句柄能精确绑定本次执行。
        final TokenStream started = stream;
        boolean registered =
                executeManager.register(
                        task.getMemoryId(),
                        messageId,
                        started.executionId(),
                        () -> ((IAgent) task.getAgent()).cancel(memoryId, started.executionId()));
        if (!registered) {
            log.info("[AgentRecovery] 已有运行注册，放弃 messageId={}", messageId);
            return false;
        }

        // 步骤 3：绑定事件出口。后台模式写事件存储，不依赖前端 SSE 长连接；
        // 前端刷新后从 afterSeq 续读，即可看到恢复后的增量输出。
        streamingMiddleware.bind(memoryId, eventFactory.createSink(messageId));

        // 步骤 4：预注册运行状态，使生命周期中间件能按 aiMessageId 推进状态并最终收口。
        lifecycleMiddleware.registerRun(memoryId, task.getMemoryId(), messageId);

        // 步骤 5：提交线程池执行，与正常提交走同一条链路。
        taskExecutor.execute(
                () -> {
                    try {
                        started.onCompleteResponse(response -> finish(memoryId, messageId))
                                .onError(error -> finish(memoryId, messageId))
                                .ignoreErrors()
                                .start();
                    } catch (RuntimeException e) {
                        streamingMiddleware.onError(memoryId, e);
                        lifecycleMiddleware.finishWithError(memoryId, e);
                        finish(memoryId, messageId);
                    }
                });
        log.info(
                "[AgentRecovery] 已接管 memoryId={}, messageId={}, newExecutionId={}, sourceExecutionId={}",
                memoryId,
                messageId,
                stream.executionId(),
                task.getSourceExecutionId());
        return true;
    }

    /** 收口：注销运行态、解绑事件出口、落事件快照并兜底补状态。 */
    private void finish(Object memoryId, long messageId) {
        executeManager.unregister(messageId);
        try {
            streamingMiddleware.unbind(memoryId);
        } catch (RuntimeException e) {
            log.warn("[AgentRecovery] 解绑事件出口失败 memoryId={}", memoryId, e);
        }
        try {
            snapshotService.saveSnapshot(messageId);
        } catch (RuntimeException ignored) {
            // 订阅端在观察到终态后会自愈快照，这里失败不影响结果。
        }
        closeIfStillRunning(messageId);
    }

    /**
     * 已完成但状态没落库时的兜底收口。
     *
     * <p>当恢复准备阶段判定"历史其实早已完整结束"时，{@code resumeStream} 返回一次性完成的 TokenStream，{@code start()}
     * 只回调完整响应、不触发初始化完成回调，消息会停在 1/2。
     */
    private void closeIfStillRunning(long messageId) {
        AgentSessionMessagePojo message = agentSessionMessageMapper.selectById(messageId);
        if (message == null || SessionStatusTransfer.isTerminal(message.getStatus())) {
            return;
        }
        Date now = new Date();
        agentSessionMessageMapper.updateStatus(
                messageId, SessionMessageStatusEnum.SUCCEEDED.getCode(), now);
        // 注意：消息上的 sessionId 是 ai_agent_session 主键，这里按主键更新。
        agentSessionMapper.updateStatus(
                message.getSessionId(), SessionStatusEnum.FINISHED.getCode(), now);
    }
}
