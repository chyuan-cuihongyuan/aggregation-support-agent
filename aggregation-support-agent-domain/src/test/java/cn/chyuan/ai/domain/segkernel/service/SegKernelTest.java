package cn.chyuan.ai.domain.segkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中文分词内核 BK1-BK7 单测（工单 0525-0531）：
 * 前缀词典 DAG/最大概率路径/HMM 未知词/用户词典屏蔽词/三模式/关键词/词条表。
 */
class SegKernelTest {

    private TrieDict baseDict() {
        TrieDict dict = new TrieDict();
        dict.add("研究", 1000);
        dict.add("研究生", 500);
        dict.add("生命", 800);
        dict.add("起源", 600);
        dict.add("学习", 700);
        return dict;
    }

    @Test
    void BK1_前缀词典与切分DAG() {
        TrieDict dict = baseDict();
        assertTrue(dict.contains("研究生"));
        assertTrue(dict.contains("研究"));
        assertFalse(dict.contains("不存在"));
        assertEquals(0L, dict.freq("不存在"));
        assertEquals(3600L, dict.totalFreq(), "总词频为词条词频和");
        List<List<Integer>> dag = dict.dag("研究生");
        assertEquals(List.of(1, 2, 3), dag.get(0), "研：单字兜底+研究+研究生");
        assertEquals(List.of(2), dag.get(1), "究：仅单字兜底");
        assertEquals(List.of(3), dag.get(2));
        assertEquals(List.of(), dict.dag("").stream().flatMap(List::stream).toList(), "空串空图");
        assertThrows(IllegalArgumentException.class, () -> dict.add("", 5L), "空词条拒绝");
        assertThrows(IllegalArgumentException.class, () -> dict.add("词", 0L), "非正词频拒绝");
        assertThrows(IllegalArgumentException.class, () -> dict.dag(null), "null 句子拒绝");
    }

    @Test
    void BK2_最大概率路径与代价() {
        TrieDict dict = baseDict();
        MaxProbPath path = new MaxProbPath();
        List<String> cut = path.cut(dict, "研究生命起源");
        assertEquals(List.of("研究", "生命", "起源"), cut, "高频词路径胜出");
        List<String> cut2 = path.cut(dict, "研究生学习");
        assertEquals(List.of("研究生", "学习"), cut2, "研究生整词胜过研究+单字");
        double costA = path.totalCost(dict, List.of("研究", "生命", "起源"));
        double costB = path.totalCost(dict, List.of("研究生", "生命", "起源"));
        assertTrue(costA < costB, "最优路径代价更小");
        assertEquals("研究生命起源", path.join(cut), "往返回拼等价");
        double oovCost = path.edgeCost(dict, "旮");
        assertTrue(oovCost > path.edgeCost(dict, "研究"), "未登录字平滑代价更高");
    }

    @Test
    void BK3_HMM未知词Viterbi解码() {
        HmmSegmenter hmm = new HmmSegmenter();
        assertEquals(1, hmm.decode("旮").size(), "单字仅 S 单字词");
        List<HmmSegmenter.Span> two = hmm.decode("旮旯");
        assertEquals(List.of(new HmmSegmenter.Span(0, 2)), two, "二字合并成词");
        List<HmmSegmenter.Span> four = hmm.decode("旮旯旮旯");
        assertEquals(List.of(new HmmSegmenter.Span(0, 2), new HmmSegmenter.Span(2, 4)), four, "四字判两词");
        List<HmmSegmenter.Span> merged = hmm.merge(
                List.of(new HmmSegmenter.Span(0, 2)),
                List.of(new HmmSegmenter.Span(2, 4), new HmmSegmenter.Span(4, 5)), 5);
        assertEquals(3, merged.size(), "词典区间与 HMM 片段有序合并");
        assertThrows(IllegalArgumentException.class, () -> hmm.merge(
                List.of(new HmmSegmenter.Span(0, 3)), List.of(new HmmSegmenter.Span(2, 4)), 5), "重叠区间拒绝");
        assertThrows(IllegalArgumentException.class, () -> hmm.decode(null));
    }

    @Test
    void BK4_用户词典强制成词与屏蔽词切开() {
        TrieDict dict = baseDict();
        MaxProbPath path = new MaxProbPath();
        UserDictOverlay overlay = new UserDictOverlay();
        overlay.addWord("研究生命", 900L);
        List<String> cut = path.cut(dict, overlay, "研究生命");
        assertEquals(List.of("研究生命"), cut, "用户词整词胜出");
        assertTrue(overlay.isUser("研究生命"));
        assertThrows(IllegalArgumentException.class, () -> overlay.addWord("词", 0L), "非正词频拒绝");
        UserDictOverlay blocker = new UserDictOverlay();
        blocker.blockWord("研究");
        List<String> cutBlocked = path.cut(dict, blocker, "研究生");
        assertEquals(List.of("研", "究", "生"), cutBlocked, "屏蔽词强制切开");
        assertTrue(blocker.isBlocked("研究"));
        assertThrows(IllegalArgumentException.class, () -> blocker.blockWord("研"), "单字无从切开拒绝");
    }

    @Test
    void BK5_三模式粒度() {
        TrieDict dict = baseDict();
        UserDictOverlay overlay = new UserDictOverlay();
        SegModes modes = new SegModes();
        List<String> exact = modes.exact(dict, overlay, "研究生学习", false);
        assertEquals(List.of("研究生", "学习"), exact);
        List<String> all = modes.cutAll(dict, overlay, "研究生");
        assertEquals(List.of("研究", "研究生"), all, "全模式枚举全部成词");
        List<String> search = modes.cutForSearch(dict, overlay, "研究生学习", false);
        assertEquals(List.of("研究生", "研究", "究生", "学习"), search, "长词追加相邻二元");
        assertTrue(search.containsAll(exact), "搜索模式包含精确词");
        List<String> hmmCut = modes.exact(dict, overlay, "研究旮旯起源", true);
        assertEquals(List.of("研究", "旮旯", "起源"), hmmCut, "OOV 游程 HMM 合并");
    }

    @Test
    void BK6_关键词TFIDF与TextRank() {
        KeywordExtractor extractor = new KeywordExtractor();
        List<List<String>> corpus = List.of(
                List.of("研究", "生命", "起源"),
                List.of("研究", "学习"),
                List.of("生命", "科学"));
        List<KeywordExtractor.Keyword> tfidf = extractor.tfidf(corpus.get(0), corpus, 2, Set.of());
        assertEquals("起源", tfidf.get(0).word(), "独有词 idf 高居首");
        assertTrue(tfidf.get(0).score() > tfidf.get(1).score());
        List<KeywordExtractor.Keyword> rank = extractor.textrank(
                List.of("研究", "生命", "起源", "研究"), 2, Set.of(), 3, 0.85d, 1.0E-6d, 100);
        assertEquals(List.of("生命", "研究"), rank.stream().map(KeywordExtractor.Keyword::word).toList(),
                "全连通图同分按词字典序（码点序）确定性");
        assertThrows(IllegalArgumentException.class, () -> extractor.tfidf(corpus.get(0), corpus, 2, null),
                "停用词表 null 拒绝");
        assertEquals(List.of(), extractor.textrank(List.of(), 3, Set.of(), 3, 0.85d, 1.0E-6d, 100), "空文档空结果");
    }

    @Test
    void BK7_词条登记来源状态与topN() {
        SegTermRegistry registry = new SegTermRegistry();
        registry.register("研究", 3L, SegTermRegistry.SOURCE_DICT);
        registry.register("生命", 2L, SegTermRegistry.SOURCE_USER);
        registry.register("旮旯", 1L, SegTermRegistry.SOURCE_HMM);
        registry.register("研究", 5L, SegTermRegistry.SOURCE_DICT);
        assertEquals(3, registry.size(), "重复词更新不新增行");
        assertTrue(registry.contains("研究"));
        assertEquals(new SegTermRegistry.Term("研究", 5L, 2, SegTermRegistry.SOURCE_DICT,
                SegTermRegistry.STATUS_ACTIVE), registry.snapshot().get(0));
        List<SegTermRegistry.Term> top = registry.topByFreq(2);
        assertEquals(List.of("研究", "生命"), top.stream().map(SegTermRegistry.Term::word).toList(), "词频降序");
        registry.delete("研究");
        assertFalse(registry.contains("研究"), "墓碑后不再活跃");
        assertEquals("生命", registry.topByFreq(2).get(0).word(), "墓碑不参与 topN");
        assertThrows(IllegalArgumentException.class, () -> registry.register("词", 1L, "BAD"), "非法来源拒绝");
    }
}
