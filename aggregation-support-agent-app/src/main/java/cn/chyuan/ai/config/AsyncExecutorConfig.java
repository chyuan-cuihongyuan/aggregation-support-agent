package cn.chyuan.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

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
     * 拒绝策略：{@link ThreadPoolExecutor.DiscardPolicy} — 当队列满且线程数达上限时，
     * 静默丢弃后续 trace 写入，确保 RAG 主链路**不被审计阻塞**（性能优先于完整审计）。
     */
    @Bean("ragTraceExecutor")
    public AsyncTaskExecutor ragTraceExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(2000);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("rag-trace-async-");
        // 优先保护 RAG 主链路，队列满时静默丢弃 trace 写入
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        log.info("初始化 RAG 追踪异步线程池 ragTraceExecutor: core={}, max={}, queue={}", 4, 16, 2000);
        return executor;
    }
}
