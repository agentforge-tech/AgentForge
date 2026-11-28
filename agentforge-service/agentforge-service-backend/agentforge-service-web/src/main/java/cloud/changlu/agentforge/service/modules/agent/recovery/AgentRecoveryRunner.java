package cloud.changlu.agentforge.service.modules.agent.recovery;

import cloud.changlu.agentforge.service.env.EnvironmentContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;

/**
 * 启动钩子：应用就绪后异步触发一次 Agent 任务恢复。
 *
 * <p>三条硬性约束：
 *
 * <ul>
 *   <li>异步：恢复要真实调用模型，不能拖住端口就绪与健康检查；
 *   <li>不抛异常：恢复是尽力而为，任何失败都不能导致应用启动失败；
 *   <li>延迟启动：等连接池、Mapper 与事件存储全部就绪。
 * </ul>
 */
@Component
public class AgentRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRecoveryRunner.class);

    private final AgentRecoveryService agentRecoveryService;
    private final EnvironmentContext environmentContext;
    private final ExecutorService taskExecutor;

    public AgentRecoveryRunner(
            AgentRecoveryService agentRecoveryService,
            EnvironmentContext environmentContext,
            @Qualifier("agentRecoveryTaskExecutor") ExecutorService taskExecutor) {
        this.agentRecoveryService = agentRecoveryService;
        this.environmentContext = environmentContext;
        this.taskExecutor = taskExecutor;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!environmentContext.isAgentRecoveryEnabled()) {
            log.info("[AgentRecovery] 未开启，跳过启动恢复");
            return;
        }
        long delay = environmentContext.getAgentRecoveryStartupDelayMs();
        taskExecutor.execute(
                () -> {
                    try {
                        if (delay > 0L) {
                            Thread.sleep(delay);
                        }
                        agentRecoveryService.recoverOnStartup();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("[AgentRecovery] 启动恢复被中断");
                    } catch (RuntimeException e) {
                        log.error("[AgentRecovery] 启动恢复异常，服务继续对外提供能力", e);
                    }
                });
    }
}
