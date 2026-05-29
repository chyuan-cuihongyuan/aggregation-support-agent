package cn.chyuan.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 审计 / RAG-Trace 异步线程池配置 — 与项目根 {@code ThreadPoolConfig} 解耦
 * <p>
 * 设计目标：
 * <ul>
 *   <li>审计与 RAG 追踪落库不再阻塞主业务链路（登录/对话/上传等）</li>
 *   <li>不同的拒绝策略：审计用 CallerRunsPolicy 保证不丢；RAG-Trace 用 DiscardPolicy 优先保护主链路吞吐</li>
 * </ul>
 * <p>
 * 注意：本类不加 {@code @EnableAsync}，由项目根 {@code ThreadPoolConfig} 统一开启。
 * 返回类型用 {@link AsyncTaskExecutor}（来自 {@code org.springframework.core.task} 包），
 * 避免与已废弃的 {@code AsyncListenableTaskExecutor} 混淆。
 */
@Slf4j
@Configuration
public class AsyncExecutorConfig {

    /**
     * 审计日志专用线程池
     * <p>
     * 拒绝策略：{@link ThreadPoolExecutor.CallerRunsPolicy} — 当队列满且线程数达上限时，
     * 由调用线程（主业务线程）兜底执行审计写入，确保审计**不丢**（合规优先于吞吐）。
     */
    @Bean("auditExecutor")
    public AsyncTaskExecutor auditExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(2000);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("audit-async-");
        // 审计不允许丢失，兜底由调用方线程执行
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 应用关闭时等待队列任务执行完成，避免审计丢失
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("初始化审计异步线程池 auditExecutor: core={}, max={}, queue={}", 4, 16, 2000);
        return executor;
    }

    /**
     * RAG 检索追踪专用线程池
     * <p>
     * 拒绝策略：自定义 {@link RejectedExecutionHandler} — 语义保持 {@code DiscardPolicy} 的丢弃行为，
     * 但每丢弃一条 trace 写入即记录 warn 日志（带累计计数 + 队列水位），
     * 解决"静默丢弃 → 数据缺失无可观测信号"的问题。
     * <p>
     * 选型说明：RAG 主链路对延迟敏感，trace 写入只是审计旁路；当负载尖峰打满队列时优先丢 trace，
     * 但运维仍需要知道丢了多少条、什么时段。
     */
    @Bean("ragTraceExecutor")
    public AsyncTaskExecutor ragTraceExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(2000);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("rag-trace-async-");
        // 优先保护 RAG 主链路，队列满时丢弃 trace 写入但留下可观测日志
        executor.setRejectedExecutionHandler(new LoggingDiscardPolicy("ragTraceExecutor"));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        log.info("初始化 RAG 追踪异步线程池 ragTraceExecutor: core={}, max={}, queue={}", 4, 16, 2000);
        return executor;
    }

    @Bean("ragRetrievalExecutor")
    public AsyncTaskExecutor ragRetrievalExecutor(
            @org.springframework.beans.factory.annotation.Value("${rag.retrieval.executor.core-size:8}") int coreSize,
            @org.springframework.beans.factory.annotation.Value("${rag.retrieval.executor.max-size:32}") int maxSize,
            @org.springframework.beans.factory.annotation.Value("${rag.retrieval.executor.queue-capacity:200}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("rag-retrieval-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        log.info("初始化 RAG 检索线程池 ragRetrievalExecutor: core={}, max={}, queue={}", coreSize, maxSize, queueCapacity);
        return executor;
    }

    /**
     * 带日志的丢弃策略 — 等价于 {@link ThreadPoolExecutor.DiscardPolicy}，但每次丢弃写入 warn 日志。
     * <p>
     * 同名线程池的所有丢弃事件累加到一个计数器，日志每条都包含累计值与当前队列大小，
     * 便于在 ELK / Grafana 上聚合统计与告警；同时避免日志爆炸由调用方在告警侧做采样。
     */
    private static final class LoggingDiscardPolicy implements RejectedExecutionHandler {
        private final String executorName;
        private final AtomicLong discardCount = new AtomicLong(0);

        LoggingDiscardPolicy(String executorName) {
            this.executorName = executorName;
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            long discarded = discardCount.incrementAndGet();
            log.warn("[{}] 队列已满，静默丢弃异步任务: 累计丢弃={}, 当前队列={}, 活跃线程={}, 线程池大小={}",
                    executorName,
                    discarded,
                    executor.getQueue().size(),
                    executor.getActiveCount(),
                    executor.getPoolSize());
        }
    }

    /**
     * Agent Memory 记忆管理专用线程池
     * <p>
     * 拒绝策略：{@link ThreadPoolExecutor.CallerRunsPolicy} — 记忆存储不能丢失，
     * 由调用线程兜底执行，确保记忆完整性。
     */
    @Bean("memoryTaskExecutor")
    public AsyncTaskExecutor memoryTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(1000);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("memory-async-");
        // 记忆存储不允许丢失，兜底由调用方线程执行
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("初始化 Agent Memory 异步线程池 memoryTaskExecutor: core={}, max={}, queue={}", 2, 8, 1000);
        return executor;
    }
}
