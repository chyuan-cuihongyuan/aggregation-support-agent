package cn.chyuan.ai.domain.kvkernel.service;

import java.util.List;
import java.util.Map;

/**
 * KV 分页端口（工单 0755 CK8，vllm PagedAttention 思想）。
 * allocate/append/step 入口统一编排/与 inferkernel 只读联动（PrefixKvCache 命中统计作准入参考形态）/
 * kv-kernel.enabled 默认关（开启才改变行为）。
 */
public interface KvPort {

    /** 会话组件 */
    record Components(BlockPool pool, PrefixCow cow, ContinuousBatcher batcher, Preemptor preemptor,
                      Watermark watermark, Engine engine) {
    }

    /** 新会话 */
    static Components open(int blockSize, int poolCapacity, int blockBudget, int prefillTokens) {
        BlockPool pool = new BlockPool(blockSize, poolCapacity);
        PrefixCow cow = new PrefixCow(pool);
        ContinuousBatcher batcher = new ContinuousBatcher(pool, blockBudget);
        Preemptor preemptor = new Preemptor(pool, batcher);
        Watermark watermark = new Watermark(pool, 0.95);
        Engine engine = new Engine(pool, batcher, preemptor, prefillTokens);
        return new Components(pool, cow, batcher, preemptor, watermark, engine);
    }

    /** 前缀缓存命中统计准入参考（inferkernel PrefixKvCache 形状数据，泛型入参不 import）：
     *  命中率高 → 预算放宽一个块位（只读联动形态） */
    static int adjustBudget(int blockBudget, Map<String, Long> prefixCacheStats) {
        long hits = prefixCacheStats.getOrDefault("hits", 0L);
        long misses = prefixCacheStats.getOrDefault("misses", 0L);
        if (hits + misses == 0) {
            return blockBudget;
        }
        return hits * 2 >= hits + misses ? blockBudget + 1 : blockBudget;
    }

    /** 统计行 */
    static String statsLine(Components c) {
        return "used=" + c.pool().usedBlocks() + "/" + c.pool().capacity()
                + " admitted=" + c.batcher().admitted()
                + " preemptions=" + c.preemptor().preemptions()
                + " cows=" + c.cow().cowCount();
    }

    /** 内存水位概览（tskernel 无关，自述） */
    static List<String> usageOverview(Components c, Iterable<SequenceBlocks> seqs) {
        return c.watermark().usagePerSeq(seqs).entrySet().stream()
                .map(e -> "seq" + e.getKey() + "=" + e.getValue() + "blocks")
                .toList();
    }
}
