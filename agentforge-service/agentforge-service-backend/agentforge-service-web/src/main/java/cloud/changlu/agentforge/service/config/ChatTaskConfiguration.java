package cloud.changlu.agentforge.service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 执行相关线程池。
 *
 * <p>两类池的定位不同：
 *
 * <ul>
 *   <li>{@code chatTaskExecutor} / {@code agentRecoveryTaskExecutor}：承载 Agent 的整轮执行（一次对话、
 *       一次恢复都是一次长时间任务，线程会被占住直到本轮结束），使用缓存线程池——任务来一个起一个、 从不排队。有界池的问题在于队列容量远大于 maxPoolSize 时，超出 core
 *       的任务全部进队列排队而不扩容， 表现为"用户点了发送但没有任何反应"。
 *   <li>{@code chatStreamExecutor}：承载 SSE 事件订阅轮询，属于高频短任务，保持有界池以免订阅端抖动时 无限创建线程。
 * </ul>
 */
@Configuration
public class ChatTaskConfiguration {

    /**
     * 对话任务执行池：每个任务代表一整轮 Agent 执行，耗时以秒到分钟计。
     *
     * <p>用缓存池保证并发对话互不排队；并发上限由模型调用本身的资源自然约束。
     */
    @Bean(name = "chatTaskExecutor", destroyMethod = "shutdown")
    public ExecutorService chatTaskExecutor() {
        return Executors.newCachedThreadPool(namedThreadFactory("agentforge-chat-"));
    }

    /** 服务启动恢复专用池：与对话池隔离，避免恢复任务与用户对话互相影响。 */
    @Bean(name = "agentRecoveryTaskExecutor", destroyMethod = "shutdown")
    public ExecutorService agentRecoveryTaskExecutor() {
        return Executors.newCachedThreadPool(namedThreadFactory("agentforge-recovery-"));
    }

    /** SSE 事件订阅池：高频短任务，保持有界，防止订阅端抖动时线程数量失控。 */
    @Bean("chatStreamExecutor")
    public Executor chatStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("agentforge-stream-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    /** 统一构造带序号命名的守护线程工厂，便于 jstack 定位与 JVM 平滑退出。 */
    private static ThreadFactory namedThreadFactory(String prefix) {
        return new ThreadFactory() {
            private final AtomicInteger sequence = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
    }
}
