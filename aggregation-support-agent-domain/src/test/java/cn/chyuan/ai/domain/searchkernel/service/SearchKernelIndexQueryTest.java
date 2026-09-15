package cn.chyuan.ai.domain.searchkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AW1-AW4 单测（工单 0396/0397/0398/0399）：索引生命周期/分词/容错拼写/排序管道。
 */
class SearchKernelIndexQueryTest {

    @Test
    void 索引添加更新版本单调与删除墓碑() {
        InMemoryInvertedIndex index = new InMemoryInvertedIndex();
        index.upsert("a", "华为手机", "旗舰机型", Map.of("cat", "数码"), 1);
        // 旧版本拒绝
        assertThrows(InMemoryInvertedIndex.VersionConflictException.class,
                () -> index.upsert("a", "华为手机", "旗舰机型", Map.of(), 1));
        // 更新生效
        index.upsert("a", "华为手机", "鸿蒙旗舰", Map.of("cat", "数码"), 2);
        assertTrue(index.posting("鸿").contains("a"));
        // 删除墓碑：倒排移除
        index.upsert("b", "苹果手机", "ios 系统", Map.of(), 1);
        index.delete("b");
        assertTrue(index.posting("ios").isEmpty());
        assertEquals(1, index.size());
        // 快照重建等价（按文档 id 对比）
        List<InMemoryInvertedIndex.Doc> snapshot = index.snapshot();
        InMemoryInvertedIndex rebuilt = new InMemoryInvertedIndex();
        rebuilt.restore(snapshot);
        assertEquals(snapshot.stream().map(InMemoryInvertedIndex.Doc::getId).toList(),
                rebuilt.snapshot().stream().map(InMemoryInvertedIndex.Doc::getId).toList());
        assertEquals(index.posting("鸿"), rebuilt.posting("鸿"));
    }

    @Test
    void 分词英数段CJK单字与归一() {
        SearchTokenizer tokenizer = new SearchTokenizer();
        assertEquals(List.of("huawei", "手", "机", "2024"), tokenizer.tokenize("Huawei 手机２０２４"));
        assertEquals(List.of("鸿", "蒙"), tokenizer.tokenize("鸿蒙"));
        // 标点空白切分
        assertEquals(List.of("a", "b"), tokenizer.tokenize("a，b"));
        assertTrue(tokenizer.tokenize("").isEmpty());
        assertTrue(tokenizer.tokenize("!!!").isEmpty());
    }

    @Test
    void 容错拼写分档阈值与代价排序() {
        TypoToleranceExpander expander = TypoToleranceExpander.defaults();
        List<String> vocabulary = List.of("huawei", "harmony", "hello", "help");
        // 短词（≤4）0 容错：helo 距 hello 为 1 → 不命中；help 距 1 → 不命中（4 字词 0 档）
        assertTrue(expander.expand("helo", vocabulary, false).isEmpty());
        // 中词 1 容错：huawe 距 huawei 1 → 命中
        List<TypoToleranceExpander.Candidate> hits = expander.expand("huawei", List.of("huawei", "harmony"), false);
        assertEquals("huawei", hits.get(0).word());
        assertEquals(0, hits.get(0).distance());
        // 距离 1 候选：huawel 距 huawei 1
        hits = expander.expand("huawel", List.of("huawei", "harmony"), false);
        assertEquals(1, hits.size());
        assertEquals("huawei", hits.get(0).word());
        // 前缀匹配
        assertEquals(List.of("harmony", "hello", "help", "huawei"),
                expander.prefixMatches("h", vocabulary));
        // 首词不容错
        assertTrue(expander.expand("huawel", List.of("huawei"), true).isEmpty());
    }

    @Test
    void 排序管道六级比较与决胜规则可解释() {
        RankingRulePipeline pipeline = new RankingRulePipeline(null);
        RankingRulePipeline.Score a = new RankingRulePipeline.Score("a", 3, 0, 5, 1, 10, 0);
        RankingRulePipeline.Score b = new RankingRulePipeline.Score("b", 2, 0, 1, 1, 2, 0);
        // words 优先：a 命中词多 → a 前
        List<RankingRulePipeline.Ranked> ranked = pipeline.rank(List.of(b, a));
        assertEquals("a", ranked.get(0).score().docId());
        assertEquals("WORDS", ranked.get(1).decidedBy());
        // 同词数：typo 小者优，decidedBy=TYPO
        RankingRulePipeline.Score c = new RankingRulePipeline.Score("c", 3, 1, 0, 0, 0, 0);
        ranked = pipeline.rank(List.of(a, c));
        assertEquals("a", ranked.get(0).score().docId());
        assertEquals("TYPO", ranked.get(1).decidedBy());
        // 规则顺序配置生效：TYPO 放最前（h 词多但容错代价高、l 词少零代价 → 两序下互换胜负）
        RankingRulePipeline.Score h = new RankingRulePipeline.Score("h", 3, 2, 0, 0, 0, 0);
        RankingRulePipeline.Score l = new RankingRulePipeline.Score("l", 1, 0, 0, 0, 0, 0);
        RankingRulePipeline typoFirst = new RankingRulePipeline(List.of(
                RankingRulePipeline.Rule.TYPO, RankingRulePipeline.Rule.WORDS));
        assertEquals("l", typoFirst.rank(List.of(h, l)).get(0).score().docId());
        assertEquals("h", pipeline.rank(List.of(h, l)).get(0).score().docId());
    }
}
