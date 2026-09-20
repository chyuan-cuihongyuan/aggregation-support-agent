package cn.chyuan.ai.domain.textkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全文检索深化内核 BG1-BG7 单测（工单 0488-0494）：
 * 分析器链/BM25/短语邻近/前缀补全/高亮/切面聚合/文档表登记。
 */
class TextKernelTest {

    @Test
    void BG1_分析器链位置与偏移() {
        AnalyzerChain analyzer = new AnalyzerChain();
        List<AnalyzerChain.Token> tokens = analyzer.analyze("The Quick brown-fox 快速狐狸");
        List<String> texts = tokens.stream().map(AnalyzerChain.Token::text).toList();
        assertFalse(texts.contains("the"), "停止词过滤");
        assertTrue(texts.contains("quick"));
        assertTrue(texts.contains("brown"), "符号切分（连字符边界）");
        assertTrue(texts.contains("快"), "CJK unigram");
        assertTrue(texts.contains("速狐"), "CJK bigram");
        for (int i = 1; i < tokens.size(); i++) {
            assertTrue(tokens.get(i).position() > tokens.get(i - 1).position(), "position 连续递增");
        }
        AnalyzerChain.Token quick = tokens.stream()
                .filter(token -> token.text().equals("quick")).findFirst().orElseThrow();
        assertEquals("quick".length(), quick.endOffset() - quick.startOffset(), "offset 保留原文位置");
    }

    @Test
    void BG2_BM25评分与可解释明细() {
        Bm25Scorer scorer = new Bm25Scorer(1.2, 0.75);
        scorer.indexDoc(1, List.of("agent", "memory", "agent", "kernel"));
        scorer.indexDoc(2, List.of("agent", "plan"));
        assertEquals(2, scorer.totalDocs());
        assertEquals(2, scorer.docFreq("agent"));
        assertEquals(1, scorer.docFreq("kernel"));
        Bm25Scorer.ScoreBreakdown common = scorer.score(1, "agent", 2);
        Bm25Scorer.ScoreBreakdown rare = scorer.score(1, "kernel", 1);
        assertTrue(rare.idf() > common.idf(), "稀有词 idf 更高");
        assertTrue(common.tfNorm() > 0);
        assertEquals(common.idf() * common.tfNorm(), common.score(), 1e-9, "score = idf × tfNorm");
        assertTrue(scorer.score(1, Map.of("agent", 2, "kernel", 1)) > 0);
    }

    @Test
    void BG3_短语连续与slop容忍() {
        PhraseProximity proximity = new PhraseProximity();
        AnalyzerChain analyzer = new AnalyzerChain();
        proximity.indexDoc(1, analyzer.analyze("machine learning model"));
        proximity.indexDoc(2, analyzer.analyze("machine deep learning"));
        assertEquals(List.of(1), proximity.phrase(List.of("machine", "learning"))
                .stream().map(PhraseProximity.PhraseHit::docId).toList(), "连续短语仅文档 1");
        var slopHits = proximity.phraseWithSlop(List.of("machine", "learning"), 1);
        assertEquals(Set.of(1, 2), Set.copyOf(slopHits.stream().map(PhraseProximity.PhraseHit::docId).toList()),
                "slop=1 覆盖跳一词");
        assertTrue(proximity.phraseWithSlop(List.of("machine", "model"), 0).isEmpty(), "位移超 slop 不命中");
    }

    @Test
    void BG4_前缀补全df排序() {
        PrefixCompleter completer = new PrefixCompleter();
        completer.register("Agent", 1);
        completer.register("agent", 2);
        completer.register("agent", 3);
        completer.register("agile", 1);
        List<PrefixCompleter.Suggestion> suggestions = completer.suggest("AG", 10);
        assertEquals("agent", suggestions.get(0).term(), "大小写不敏感 + df 最高在前");
        assertEquals(3, suggestions.get(0).docFrequency());
        assertEquals(2, suggestions.size());
        assertEquals(1, completer.suggest("agile", 5).get(0).docFrequency());
    }

    @Test
    void BG5_高亮片段与偏移标注() {
        Highlighter highlighter = new Highlighter(2, 20);
        List<Highlighter.Fragment> fragments = highlighter.highlight(
                "Agent memory agent kernel and more agent context here", Set.of("agent", "kernel"));
        assertFalse(fragments.isEmpty());
        assertTrue(fragments.size() <= 2, "片段数上限");
        assertTrue(fragments.get(0).text().toLowerCase().contains("<em>agent</em>"), "命中词标注（原文大小写保留）");
        assertTrue(fragments.get(0).text().length() <= 20 + "<em></em>".length(), "片段长度上限含标记");
    }

    @Test
    void BG6_切面聚合与下钻() {
        FacetAggregator aggregator = new FacetAggregator();
        aggregator.indexDoc(1, Map.of("env", "prod", "region", "cn"));
        aggregator.indexDoc(2, Map.of("env", "prod", "region", "us"));
        aggregator.indexDoc(3, Map.of("env", "dev", "region", "cn"));
        List<FacetAggregator.Bucket> envs = aggregator.aggregate("env");
        assertEquals(2, envs.size());
        assertEquals("prod", envs.get(0).value(), "计数降序");
        assertEquals(2, envs.get(0).count());
        List<FacetAggregator.Bucket> drilled = aggregator.drillDown("env", "prod", "region");
        assertEquals(2, drilled.size());
        assertEquals("cn", drilled.get(0).value(), "同频字典序确定性");
    }

    @Test
    void BG7_文档表登记与删除幂等() {
        TextDocRegistry registry = new TextDocRegistry();
        AnalyzerChain analyzer = new AnalyzerChain();
        TextDocRegistry.DocRow row = registry.index(7, Map.of("title", "Hello World", "body", "内容"),
                analyzer.analyze("Hello World 内容"));
        assertEquals(TextDocRegistry.Status.INDEXED, row.status());
        assertTrue(row.fieldTextJson().contains("\"body\":\"内容\""), "字段文本 JSON 键字典序");
        assertEquals(analyzer.analyze("Hello World 内容").size(), row.termCount());
        assertTrue(registry.markDeleted(7));
        assertFalse(registry.markDeleted(7), "重复删除幂等");
        assertTrue(registry.indexedDocs().isEmpty(), "DELETED 不在索引视图");
    }
}
