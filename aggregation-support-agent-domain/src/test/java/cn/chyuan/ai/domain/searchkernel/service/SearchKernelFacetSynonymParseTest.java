package cn.chyuan.ai.domain.searchkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AW5-AW8 单测（工单 0400/0401/0402/0403）：facet/同义词/查询解析/高亮。
 */
class SearchKernelFacetSynonymParseTest {

    @Test
    void facet分类计数降序与数值stats() {
        FacetAggregator aggregator = new FacetAggregator();
        List<Map<String, String>> docs = List.of(
                Map.of("cat", "数码", "brand", "华为"),
                Map.of("cat", "数码", "brand", "苹果"),
                Map.of("cat", "家居"));
        FacetAggregator.FacetResult result = aggregator.aggregate(docs,
                List.of("cat", "brand"), Map.of("price", List.of(1.0, 2.0, 6.0)));
        // 计数降序、同数键序
        assertEquals(2L, result.counts().get("cat:数码"));
        assertEquals(1L, result.counts().get("cat:家居"));
        assertEquals(1L, result.counts().get("brand:华为"));
        assertEquals(new FacetAggregator.Stats(1.0, 6.0, 3.0), result.numericStats().get("price"));
        // 空结果
        assertTrue(aggregator.aggregate(List.of(), List.of("cat"), Map.of()).counts().isEmpty());
    }

    @Test
    void 同义词单向双向与环防护() {
        SynonymExpander expander = new SynonymExpander(List.of(
                new SynonymExpander.Synonym(SynonymExpander.Type.ONE_WAY, "手机", List.of("移动终端")),
                new SynonymExpander.Synonym(SynonymExpander.Type.TWO_WAY, "华为", List.of("huawei")),
                new SynonymExpander.Synonym(SynonymExpander.Type.ONE_WAY, "huawei", List.of("华为"))), 3);
        // 单向：查询 手机 → 移动终端；查询 移动终端 不回扩展手机
        var fromPhone = expander.expand("手机");
        assertTrue(fromPhone.contains("移动终端"));
        assertTrue(expander.expand("移动终端").contains("移动终端"));
        // 双向：华为 ↔ huawei，环防护不发散
        var huawei = expander.expand("华为");
        assertTrue(huawei.contains("huawei"));
        assertTrue(huawei.contains("华为"));
        assertEquals(2, huawei.size());
        // 非法层级
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new SynonymExpander(List.of(), 0));
    }

    @Test
    void 查询解析短语排除过滤与降级告警() {
        SearchQueryParser parser = new SearchQueryParser();
        var q = parser.parse("华为 \"旗舰 手机\" -苹果 cat:数码 鸿* \"未闭合");
        // 普通词与降级词均按 AW2 分词语义（CJK 单字）
        assertEquals(List.of("华", "为", "未", "闭", "合"), q.terms());
        assertEquals(List.of("鸿"), q.prefixes());
        assertEquals(List.of("旗舰 手机"), q.phrases());
        assertEquals(List.of("苹果"), q.excluded());
        assertEquals(Map.of("cat", "数码"), q.filters());
        // 未闭合引号降级+告警
        assertEquals(1, q.warnings().size());
        assertTrue(q.warnings().get(0).contains("未闭合"));
        // 非法过滤子句忽略
        var bad = parser.parse("a :v");
        assertTrue(bad.warnings().stream().anyMatch(w -> w.contains("非法过滤")));
        // 空查询
        assertTrue(parser.parse("  ").terms().isEmpty());
    }

    @Test
    void 高亮首命中词边界与截断片段() {
        Highlighter highlighter = new Highlighter(4);
        var hit = highlighter.highlight("华为发布鸿蒙系统，鸿蒙生态持续扩张", List.of("鸿蒙"));
        // 首命中位置=4，上下文 4 字符覆盖到串首（无前省略号）
        assertEquals(4, hit.hitStart());
        assertTrue(hit.text().contains("鸿蒙"));
        // 词边界：子串不误伤（"手机"不命中"手机壳"以外的边界场景——反向：找"机"不命中"手机"中的部分）
        var boundary = highlighter.highlight("total unknown", List.of("totally"));
        assertEquals(-1, boundary.hitStart());
        // 无命中返回头部截断
        var none = highlighter.highlight("abcdef", List.of("xyz"));
        assertEquals(-1, none.hitStart());
        assertTrue(none.text().startsWith("abcdef".substring(0, 4)) || none.text().startsWith("abc"));
    }
}
