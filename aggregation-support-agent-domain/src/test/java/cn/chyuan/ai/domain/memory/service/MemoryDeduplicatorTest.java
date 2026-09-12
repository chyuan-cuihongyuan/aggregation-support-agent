package cn.chyuan.ai.domain.memory.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 记忆去重合并单测（工单 0233 AE6）：近似判定/合并策略/不误伤。 */
class MemoryDeduplicatorTest {

    @Test
    void 近似合并频次累加() {
        MemoryDeduplicator dedup = new MemoryDeduplicator(0.8);
        MemoryDeduplicator.DedupResult result = dedup.dedup(List.of(
                new MemoryDeduplicator.MemoryItem("m1", "用户偏好使用深色主题", 2, 100),
                new MemoryDeduplicator.MemoryItem("m2", "用户偏好使用深色主题设置", 1, 200),
                new MemoryDeduplicator.MemoryItem("m3", "完全不同的话题内容另外", 5, 50)));
        // m1 与 m2 近似合并：频次高者胜（m1 频次 2 > m2 的 1，但合并后频次累加 3）
        assertEquals(2, result.kept().size());
        assertEquals("m1", result.mergedInto().get("m2"));
        MemoryDeduplicator.MemoryItem merged = result.kept().get(0);
        assertEquals(3, merged.accessCount());
        assertEquals(200, merged.updatedAt(), "时间取新");
        // 文本取更长（近似对里更长者）
        assertTrue(merged.text().contains("设置"));
    }

    @Test
    void 不误伤不同内容() {
        MemoryDeduplicator dedup = new MemoryDeduplicator(0.8);
        MemoryDeduplicator.DedupResult result = dedup.dedup(List.of(
                new MemoryDeduplicator.MemoryItem("a", "今天天气晴朗适合出行", 1, 1),
                new MemoryDeduplicator.MemoryItem("b", "数据库连接池需要调优", 1, 2)));
        assertEquals(2, result.kept().size());
        assertTrue(result.mergedInto().isEmpty());
    }

    @Test
    void 相似度与空输入() {
        assertEquals(1.0, MemoryDeduplicator.similarity("深色主题设置", "深色主题设置"));
        assertTrue(MemoryDeduplicator.similarity("完全不同甲", "数据库连接池乙") < 0.5);
        assertEquals(0.0, MemoryDeduplicator.similarity("", "abc"));
        assertTrue(new MemoryDeduplicator().dedup(null).kept().isEmpty());
        assertTrue(new MemoryDeduplicator(0.5).dedup(List.of()).mergedInto().isEmpty());
        // Map.copyOf 空合并映射安全
        Map<String, String> empty = Map.copyOf(Map.of());
        assertTrue(empty.isEmpty());
    }
}
