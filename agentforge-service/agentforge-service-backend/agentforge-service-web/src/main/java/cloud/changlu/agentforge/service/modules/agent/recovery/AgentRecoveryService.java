package cloud.changlu.agentforge.service.modules.agent.recovery;

import cloud.changlu.agentforge.service.env.EnvironmentContext;
import cloud.changlu.agentforge.service.modules.agent.domain.config.AgentRuntimeConfig;
import cloud.changlu.agentforge.service.modules.agent.runtime.base.IAgentManager;
import cloud.changlu.agentforge.service.modules.agent.runtime.context.AgentInitContext;
import cloud.changlu.agentforge.service.modules.agent.runtime.execute.CacheExecuteManager;
import cloud.changlu.agentforge.service.modules.agent.runtime.factory.AgentManagerFactory;
import cloud.changlu.agentforge.service.modules.agent.service.impl.AgentRuntimeConfigResolver;
import cloud.changlu.agentforge.service.modules.session.dao.AgentSessionMapper;
import cloud.changlu.agentforge.service.modules.session.domain.RecoverableSessionPojo;
import cloud.changlu.agentforge.service.modules.session.enums.SessionDebugEnum;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

/**
 * 服务启动恢复的编排层：扫描候选、逐条初始化 Agent、交给执行器接管。
 *
 * <p>判定口径只有一条：<b>消息处于运行态（CREATED/RUNNING），且运行注册表里没有这条消息</b>。 中断态与终态都不会进入候选集。
 */
@Service
public class AgentRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(AgentRecoveryService.class);

    private final EnvironmentContext environmentContext;
    private final AgentSessionMapper agentSessionMapper;
    private final AgentRuntimeConfigResolver runtimeConfigResolver;
    private final AgentManagerFactory agentManagerFactory;
    private final AgentRecoveryExecutor recoveryExecutor;
    private final CacheExecuteManager executeManager;

    public AgentRecoveryService(
            EnvironmentContext environmentContext,
            AgentSessionMapper agentSessionMapper,
            AgentRuntimeConfigResolver runtimeConfigResolver,
            AgentManagerFactory agentManagerFactory,
            AgentRecoveryExecutor recoveryExecutor,
            CacheExecuteManager executeManager) {
        this.environmentContext = environmentContext;
        this.agentSessionMapper = agentSessionMapper;
        this.runtimeConfigResolver = runtimeConfigResolver;
        this.agentManagerFactory = agentManagerFactory;
        this.recoveryExecutor = recoveryExecutor;
        this.executeManager = executeManager;
    }

    /** 启动恢复入口：扫描一次候选并逐个接管，任何单条失败都不影响其它会话。 */
    public void recoverOnStartup() {
        if (!environmentContext.isAgentRecoveryEnabled()) {
            log.info("[AgentRecovery] 未开启，跳过");
            return;
        }
        int days = environmentContext.getAgentRecoveryLookbackDays();
        Date since = Date.from(Instant.now().minus(days, ChronoUnit.DAYS));

        List<RecoverableSessionPojo> candidates =
                agentSessionMapper.selectRecoverableSessions(since);
        if (candidates == null || candidates.isEmpty()) {
            log.info("[AgentRecovery] 窗口={}天, 无待恢复会话", days);
            return;
        }
        log.info("[AgentRecovery] 窗口={}天, 命中候选={}", days, candidates.size());

        int resumed = 0;
        int skipped = 0;
        int failed = 0;
        for (RecoverableSessionPojo candidate : candidates) {
            try {
                if (recoverOne(candidate)) {
                    resumed++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("[AgentRecovery] 恢复失败 sessionId={}", candidate.getSessionId(), e);
            }
        }
        log.info(
                "[AgentRecovery] 完成 { scanned={}, resumed={}, skipped={}, failed={} }",
                candidates.size(),
                resumed,
                skipped,
                failed);
    }

    /**
     * 恢复单条候选。
     *
     * @return true 表示已接管一轮；false 表示跳过（已在跑、Agent 不可用等）
     */
    private boolean recoverOne(RecoverableSessionPojo candidate) {
        boolean debug = SessionDebugEnum.DEBUG.getCode().equals(candidate.getIsDebug());

        // 步骤 1：解析运行时配置。debug 读草稿表，正式读发布快照表。
        AgentRuntimeConfig config = runtimeConfigResolver.resolve(candidate.getAid(), debug);
        IAgentManager<Object, Object> manager =
                agentManagerFactory.getAgentManagerByType(config.getType());

        // 步骤 2：初始化（或复用）Agent 实例。粒度是 aid + channel，同一 aid 下所有会话共享。
        Object agent =
                manager.getAgentAndInitAgent(
                        AgentInitContext.builder()
                                .aid(config.getAid())
                                .agentName(config.getName())
                                .agentConfig(config.getAgentConfig())
                                .debug(debug)
                                .channelId(AgentInitContext.WEBSITE_CHANNEL_ID)
                                .build());

        // 步骤 3：注册检查。重启后注册表必然为空，这一步只挡住"进程内其实还在跑"的重复投递。
        if (executeManager.isRunning(candidate.getSessionId(), candidate.getAiMessageId())) {
            log.info(
                    "[AgentRecovery] 正在执行中，跳过 sessionId={}, messageId={}",
                    candidate.getSessionId(),
                    candidate.getAiMessageId());
            return false;
        }

        log.info(
                "[AgentRecovery] 开始恢复 sessionId={}, aid={}, messageId={}, sourceExecutionId={}",
                candidate.getSessionId(),
                config.getAid(),
                candidate.getAiMessageId(),
                candidate.getExecutionId());

        // 步骤 4：交给执行器接管这一轮。
        return recoveryExecutor.resume(
                AgentResumeTask.builder()
                        .memoryId(candidate.getSessionId())
                        .aid(config.getAid())
                        .aiMessageId(candidate.getAiMessageId())
                        .userId(candidate.getUserId())
                        .debug(debug)
                        .sourceExecutionId(candidate.getExecutionId())
                        .agent(agent)
                        .build());
    }
}
