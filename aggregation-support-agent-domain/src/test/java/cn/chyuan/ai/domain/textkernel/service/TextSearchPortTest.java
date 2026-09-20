package cn.chyuan.ai.domain.textkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索端口+混合加权排序单测（工单 0495 BG8）：
 * 最终分 = BM25×字段权重 + recency 半衰加权，相关性排序确定性。
 */
class TextSearchPortTest {

    @Test
    void BG8_混合加权排序() {
        long now = 1_000_000_000L;
        TextSearchPort.InMemoryTextSearch search = new TextSearchPort.InMemoryTextSearch(1.5, 86_400_000.0, now);
        AnalyzerChain analyzer = new AnalyzerChain();
        search.index(1, analyzer.analyze("agent memory system design"), now);
        search.index(2, analyzer.analyze("agent agent agent everything"), now - 10_000_000L);
        search.index(3, analyzer.analyze("unrelated document entirely"), now - 10_000_000L);
        List<TextSearchPort.Hit> hits = search.search(Map.of("agent", 1));
        assertEquals(2, hits.size(), "零分文档（无命中）不返回");
        assertEquals(2, hits.get(0).docId(), "agent 高频文档 BM25 更高");
        assertTrue(hits.get(0).finalScore() > hits.get(0).bm25Score(), "新近文档获得 recency 加成");
        assertTrue(hits.get(1).bm25Score() < hits.get(0).bm25Score());
        assertEquals(1, hits.get(1).docId(), "单次命中文档排第二");
    }

    @Test
    void BG8_旧文档recency衰减排序反超() {
        long now = 10_000_000_000L;
        TextSearchPort.InMemoryTextSearch search = new TextSearchPort.InMemoryTextSearch(1.0, 1000.0, now);
        AnalyzerChain analyzer = new AnalyzerChain();
        search.index(1, analyzer.analyze("kernel tuning"), now);
        search.index(2, analyzer.analyze("kernel kernel kernel tuning"), now - 1_000_000L);
        List<TextSearchPort.Hit> hits = search.search(Map.of("kernel", 1, "tuning", 1));
        assertEquals(1, hits.get(0).docId(), "半衰 1000ms 下旧文档加成趋零，新文档反超");
        assertEquals(hits.get(0).bm25Score(), hits.get(0).recencyBonus(), 1e-9, "age=0 加成=BM25 分本身");
        assertTrue(hits.get(1).recencyBonus() < hits.get(1).bm25Score(), "旧文档加成衰减");
    }
}
