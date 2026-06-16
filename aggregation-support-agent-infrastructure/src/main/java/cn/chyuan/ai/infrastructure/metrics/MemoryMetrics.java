package cn.chyuan.ai.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 四层记忆架构监控指标
 * <p>
 * 采集各层操作的计数、耗时、缓存命中等指标，
 * 通过 Micrometer 暴露给 Prometheus。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryMetrics {

    private final MeterRegistry meterRegistry;

    // ===== 感知记忆层指标 =====

    /**
     * 记录感知记忆层输入处理
     */
    public void recordSensoryInput(String result, long durationMs) {
        Counter.builder("memory.sensory.input")
                .tag("result", result) // filtered / accepted
                .register(meterRegistry)
                .increment();

        Timer.builder("memory.sensory.duration")
                .tag("operation", "input")
                .register(meterRegistry)
                .record(Duration.ofMillis(durationMs));
    }

    // ===== 短期记忆层指标 =====

    /**
     * 记录短期记忆消息添加
     */
    public void recordShortTermAdd(String strategy, long durationMs) {
        Counter.builder("memory.shortterm.add")
                .tag("strategy", strategy)
                .register(meterRegistry)
                .increment();

        Timer.builder("memory.shortterm.duration")
                .tag("operation", "add")
                .register(meterRegistry)
                .record(Duration.ofMillis(durationMs));
    }

    /**
     * 记录短期记忆消息获取
     */
    public void recordShortTermGet(String strategy, int messageCount, long durationMs) {
        Counter.builder("memory.shortterm.get")
                .tag("strategy", strategy)
                .register(meterRegistry)
                .increment();

        meterRegistry.gauge("memory.shortterm.message_count", messageCount);

        Timer.builder("memory.shortterm.duration")
                .tag("operation", "get")
                .register(meterRegistry)
                .record(Duration.ofMillis(durationMs));
    }

    /**
     * 记录短期记忆压缩事件
     */
    public void recordShortTermCompression(String strategy) {
        Counter.builder("memory.shortterm.compression")
                .tag("strategy", strategy)
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录短期记忆卸载事件
     */
    public void recordShortTermOffload() {
        Counter.builder("memory.shortterm.offload")
                .register(meterRegistry)
                .increment();
    }

    // ===== 长期记忆层指标 =====

    /**
     * 记录长期记忆检索
     */
    public void recordLongTermRecall(String source, int resultCount, long durationMs) {
        Counter.builder("memory.longterm.recall")
                .tag("source", source) // vector / graph / hybrid
                .register(meterRegistry)
                .increment();

        meterRegistry.gauge("memory.longterm.recall.result_count", resultCount);

        Timer.builder("memory.longterm.recall.duration")
                .tag("source", source)
                .register(meterRegistry)
                .record(Duration.ofMillis(durationMs));
    }

    /**
     * 记录长期记忆存储
     */
    public void recordLongTermStore(String type, long durationMs) {
        Counter.builder("memory.longterm.store")
                .tag("type", type) // episodic / semantic / entity
                .register(meterRegistry)
                .increment();

        Timer.builder("memory.longterm.store.duration")
                .tag("type", type)
                .register(meterRegistry)
                .record(Duration.ofMillis(durationMs));
    }

    /**
     * 记录长期记忆抽象提炼
     */
    public void recordLongTermAbstraction(int inputCount, int outputCount, long durationMs) {
        Counter.builder("memory.longterm.abstraction")
                .register(meterRegistry)
                .increment();

        meterRegistry.gauge("memory.longterm.abstraction.input_count", inputCount);
        meterRegistry.gauge("memory.longterm.abstraction.output_count", outputCount);

        Timer.builder("memory.longterm.abstraction.duration")
                .register(meterRegistry)
                .record(Duration.ofMillis(durationMs));
    }

    // ===== 实体记忆层指标 =====

    /**
     * 记录实体提取
     */
    public void recordEntityExtraction(int entityCount, long durationMs) {
        Counter.builder("memory.entity.extraction")
                .register(meterRegistry)
                .increment();

        meterRegistry.gauge("memory.entity.extraction.count", entityCount);

        Timer.builder("memory.entity.duration")
                .tag("operation", "extraction")
                .register(meterRegistry)
                .record(Duration.ofMillis(durationMs));
    }

    /**
     * 记录知识图谱查询
     */
    public void recordGraphQuery(String queryType, int resultCount, long durationMs) {
        Counter.builder("memory.graph.query")
                .tag("type", queryType) // entity_lookup / relation_query / path_finding
                .register(meterRegistry)
                .increment();

        Timer.builder("memory.graph.query.duration")
                .tag("type", queryType)
                .register(meterRegistry)
                .record(Duration.ofMillis(durationMs));
    }

    // ===== 记忆整合指标 =====

    /**
     * 记录记忆整合操作
     */
    public void recordConsolidation(String type, int count, long durationMs) {
        Counter.builder("memory.consolidation")
                .tag("type", type) // dedup / merge / abstract / expire / conflict
                .register(meterRegistry)
                .increment(count);

        Timer.builder("memory.consolidation.duration")
                .tag("type", type)
                .register(meterRegistry)
                .record(Duration.ofMillis(durationMs));
    }

    // ===== 统一检索指标 =====

    /**
     * 记录混合检索
     */
    public void recordHybridRetrieval(String strategy, int resultCount, long durationMs) {
        Counter.builder("memory.hybrid.retrieval")
                .tag("strategy", strategy) // vector_first / graph_first / parallel
                .register(meterRegistry)
                .increment();

        meterRegistry.gauge("memory.hybrid.retrieval.result_count", resultCount);

        Timer.builder("memory.hybrid.retrieval.duration")
                .tag("strategy", strategy)
                .register(meterRegistry)
                .record(Duration.ofMillis(durationMs));
    }

    // ===== 缓存指标 =====

    /**
     * 记录缓存命中/未命中
     */
    public void recordCacheHit(String layer, boolean hit) {
        Counter.builder("memory.cache")
                .tag("layer", layer) // sensory / shortterm / longterm / entity
                .tag("result", hit ? "hit" : "miss")
                .register(meterRegistry)
                .increment();
    }

    // ===== 错误指标 =====

    /**
     * 记录错误
     */
    public void recordError(String layer, String operation, String errorType) {
        Counter.builder("memory.error")
                .tag("layer", layer) // sensory / shortterm / longterm / entity / consolidation
                .tag("operation", operation)
                .tag("type", errorType)
                .register(meterRegistry)
                .increment();
    }

    // ===== 容量指标 =====

    /**
     * 更新记忆容量计数
     */
    public void updateMemoryCount(String layer, int count) {
        AtomicInteger gauge = new AtomicInteger(count);
        meterRegistry.gauge("memory.count", io.micrometer.core.instrument.Tags.of("layer", layer), gauge);
    }

    /**
     * 记录 Context Window 使用率
     */
    public void recordContextWindowUsage(double usagePercent) {
        meterRegistry.gauge("memory.context_window.usage_percent", usagePercent);
    }
}
