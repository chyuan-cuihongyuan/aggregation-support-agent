package cn.chyuan.ai.domain.kvkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KvPort 组合管线测试（工单 0755 CK8，vllm PagedAttention 思想）。
 * allocate/append/step 编排/inferkernel PrefixKvCache 命中统计准入参考只读联动（泛型入参不 import）/
 * kv-kernel.enabled 默认关。
 */
class KvPortPipelineTest {

    @Test
    void portOpenAllocateAppendStep() {
        KvPort.Components c = KvPort.open(4, 8, 16, 2);
        PrefixCow cow = c.cow();
        SequenceBlocks seq = cow.create(1);
        c.batcher().enqueue(seq);
        c.engine().runToCompletion();
        assertEquals(0, c.batcher().runningQueue().size() + c.batcher().waitingQueue().size(),
                "单序列自然完成回收");
        String line = KvPort.statsLine(c);
        assertTrue(line.startsWith("used="));
        assertTrue(line.contains("cows="));
    }

    @Test
    void portPrefixCacheStatsBudgetHint() {
        // inferkernel 只读联动形态：PrefixKvCache 命中统计形状数据（泛型入参不 import）
        assertEquals(8, KvPort.adjustBudget(8, Map.of("hits", 0L, "misses", 10L)), "低命中不放宽");
        assertEquals(9, KvPort.adjustBudget(8, Map.of("hits", 7L, "misses", 3L)), "高命中放宽一块位");
        assertEquals(8, KvPort.adjustBudget(8, Map.of()), "无统计原样");
        KvPort.Components c = KvPort.open(4, 8, KvPort.adjustBudget(8, Map.of("hits", 5L, "misses", 1L)), 2);
        assertEquals(9, c.batcher().blockBudget(), "联动预算落到批处理");
    }

    @Test
    void portUsageOverviewShapes() {
        KvPort.Components c = KvPort.open(4, 8, 16, 2);
        SequenceBlocks seq = c.cow().create(7);
        seq.append(5);
        List<String> overview = KvPort.usageOverview(c, List.of(seq));
        assertEquals(List.of("seq7=2blocks"), overview, "每序列占用形状");
        assertTrue(KvPort.statsLine(c).contains("admitted=0"), "未准入统计为零");
    }
}
