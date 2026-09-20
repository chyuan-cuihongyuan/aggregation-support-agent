package cn.chyuan.ai.domain.storekernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 存储端口+组合管线单测（工单 0479 BE8）：
 * StorePort put/get/scan/compact 四面 + 批量写 + 统计快照 + 默认关口径。
 */
class StorePortPipelineTest {

    @Test
    void BE8_端口组合管线() {
        StorePort.InMemoryLsmStore store = new StorePort.InMemoryLsmStore(3, 2);
        List<Long> sequences = store.batchPut(List.of(
                new String[]{"cfg/pool", "8"},
                new String[]{"cfg/timeout", "30"},
                new String[]{"db/host", "pg-1"}));
        assertEquals(3, sequences.size());
        assertTrue(sequences.get(0) < sequences.get(1) && sequences.get(1) < sequences.get(2), "批量序列号单调");
        assertEquals("8", store.get("cfg/pool"));
        List<SstSegment.Row> scanned = store.scanPrefix("cfg/");
        assertEquals(2, scanned.size());
        store.delete("cfg/timeout");
        assertNull(store.get("cfg/timeout"));
        assertTrue(store.scanPrefix("cfg/").stream().noneMatch(row -> row.key().equals("cfg/timeout")));
        LsmEngine.Stats stats = store.stats();
        assertTrue(stats.sequence() >= 4, "统计快照含序列号游标");
    }

    @Test
    void BE8_第30表登记联动口径() {
        StorePort.InMemoryLsmStore store = new StorePort.InMemoryLsmStore(3, 2);
        for (int i = 0; i < 6; i++) {
            store.put("seg/" + i, "v" + i);
        }
        LsmEngine.Stats stats = store.stats();
        assertTrue(stats.l0Segments() + stats.levelSegments()[1] > 0, "触发 flush/compaction 后有段产生（第 30 表登记来源）");
    }
}
