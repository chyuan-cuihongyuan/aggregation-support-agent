package cn.chyuan.ai.domain.fuzzkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模糊匹配内核测试（工单 0769-0776 CM1-CM8，fzf 思想）。
 * 子序列与大小写智能/计分权表/回溯区间/排序稳定/多模式 AND 否定/高亮合并/历史缓存/端口编排。
 */
class FuzzKernelTest {

    @Test
    void subsequenceAndEmptyCases() {
        FuzzMatcher m = new FuzzMatcher();
        assertTrue(m.isSubsequence("hlo", "hello"));
        assertFalse(m.isSubsequence("olh", "hello"));
        assertTrue(m.match("", "anything").isPresent(), "空模式匹配一切");
        assertEquals(0, m.match("", "anything").get().score());
        assertTrue(m.match("zz", "hello").isEmpty(), "不可匹配");
        assertTrue(m.match("toolongpattern", "ab").isEmpty(), "模式长于文本");
        assertTrue(m.match("a", "").isEmpty(), "空文本拒绝");
    }

    @Test
    void caseSmart() {
        FuzzMatcher m = new FuzzMatcher();
        assertTrue(m.match("fb", "FooBar").isPresent(), "全小写模式不敏感");
        assertTrue(m.match("FB", "FooBar").isPresent(), "含大写模式敏感且命中");
        assertTrue(m.match("FB", "foobar").isEmpty(), "大写模式拒绝小写文本");
    }

    @Test
    void boundaryBonusOrdering() {
        FuzzMatcher m = new FuzzMatcher();
        assertEquals(m.weights().start(), m.bonusAt("foo", 0), "串首");
        assertEquals(m.weights().white(), m.bonusAt("a b", 2), "空白后");
        assertEquals(m.weights().boundary(), m.bonusAt("a-b", 2), "非词字符后");
        assertEquals(m.weights().camel(), m.bonusAt("aB", 1), "驼峰");
        assertEquals(0, m.bonusAt("ab", 1), "词中无奖励");
    }

    @Test
    void consecutiveBeatsGapped() {
        FuzzMatcher m = new FuzzMatcher();
        int connected = m.match("ab", "abX").get().score();
        int gapped = m.match("ab", "aXb").get().score();
        assertTrue(connected > gapped, "连续优于跨隙");
        int shortGap = m.match("ab", "aXb").get().score();
        int longGap = m.match("ab", "aXXXb").get().score();
        assertTrue(shortGap > longGap, "间隙越长惩罚越重");
    }

    @Test
    void weightsConfigurable() {
        List<String> texts = List.of("a b", "ab");
        List<FuzzMatcher.Candidate> defaultRank = new FuzzMatcher().rank("ab", texts);
        assertEquals("a b", defaultRank.get(0).text(), "默认权表下空白边界奖励胜出");
        FuzzMatcher.Weights heavyGap = FuzzMatcher.Weights.defaults().withGapStart(100);
        List<FuzzMatcher.Candidate> customRank = new FuzzMatcher(heavyGap).rank("ab", texts);
        assertEquals("ab", customRank.get(0).text(), "加大间隙起步惩罚后连续胜出");
    }

    @Test
    void backtracePositionsAndSpan() {
        FuzzMatcher m = new FuzzMatcher();
        Optional<FuzzMatcher.Result> r = m.match("fb", "fooBar baz");
        assertTrue(r.isPresent());
        assertEquals(List.of(0, 3), r.get().positions(), "回溯取驼峰边界而非更远空白边界");
        assertEquals(0, r.get().start());
        assertEquals(4, r.get().end());
        List<Integer> positions = m.match("abc", "xaxbxcabc").get().positions();
        assertEquals(List.of(6, 7, 8), positions, "取尾部连续最优");
        for (int i = 1; i < positions.size(); i++) {
            assertTrue(positions.get(i) > positions.get(i - 1), "位置严格递增");
        }
    }

    @Test
    void rankingStableAndTopN() {
        FuzzMatcher m = new FuzzMatcher();
        List<FuzzMatcher.Candidate> ranked = m.rank("beta", List.of("beta2", "alpha", "beta"));
        assertEquals(List.of("beta", "beta2"), ranked.stream().map(FuzzMatcher.Candidate::text).toList(),
                "平票字典序稳定");
        assertEquals(22.0, ranked.get(0).normalized(), 1e-9, "归一化=分/模式长度");
        assertEquals(1, m.rank("beta", List.of("beta2", "beta"), 1).size(), "Top-N 截取");
        assertTrue(m.rank("zz", List.of("alpha")).isEmpty());
    }

    @Test
    void multiPatternParseAndAnd() {
        List<FuzzQuery.Term> terms = FuzzQuery.parse("foo !bar baz\\ qux");
        assertEquals(3, terms.size());
        assertEquals("foo", terms.get(0).text());
        assertFalse(terms.get(0).negated());
        assertTrue(terms.get(1).negated(), "! 前缀否定");
        assertEquals("bar", terms.get(1).text());
        assertEquals("baz qux", terms.get(2).text(), "反斜杠转义空白");
        assertTrue(terms.get(2).negated() == false);
    }

    @Test
    void multiPatternMatchSemantics() {
        FuzzQuery q = new FuzzQuery();
        assertTrue(q.matches("foo bar", "foo and bar"), "AND 全中");
        assertFalse(q.matches("foo !bar", "foo and bar"), "否定模式命中即拒绝");
        assertTrue(q.matches("!zzz", "hello"), "仅否定查询放行未命中者");
        assertFalse(q.matches("!zzz", "pizzazz"));
        assertTrue(q.matches("a\\ b", "aXb-Y a b"), "转义空白按字面子序列匹配");
        assertFalse(q.matches("a\\ b", "aXb"), "无空白字面不匹配");
        assertEquals(List.of("foo and bar"), q.filter("foo bar", List.of("foo and bar", "foo only")));
        assertEquals(2, q.search("foo bar", List.of("foo and bar", "bar and foo")).size());
    }

    @Test
    void highlightMergedRanges() {
        FuzzQuery q = new FuzzQuery();
        List<FuzzMatcher.Range> split = q.highlight("fb", "fooBar baz");
        assertEquals(List.of(new FuzzMatcher.Range(0, 1), new FuzzMatcher.Range(3, 4)), split);
        List<FuzzMatcher.Range> merged = q.highlight("ab", "abXab");
        assertEquals(List.of(new FuzzMatcher.Range(0, 2)), merged, "连续命中合并");
        assertTrue(q.highlight("zz", "hello").isEmpty(), "无命中空区间");
        assertThrows(IllegalArgumentException.class, () -> new FuzzMatcher.Range(2, 1), "end<start 拒绝");
        assertThrows(IllegalArgumentException.class, () -> new FuzzMatcher.Range(-1, 1), "负起点拒绝");
    }

    @Test
    void historyCountsAndCacheInvalidation() {
        FuzzHistory h = new FuzzHistory();
        h.record("beta");
        h.record("beta");
        h.record("alpha");
        assertEquals(2, h.history().get(0).count());
        assertEquals("beta", h.history().get(0).query(), "频次降序");
        List<String> corpus = List.of("beta", "beta2", "alpha");
        h.rankCached(0, "beta", q -> corpus);
        h.rankCached(0, "beta", q -> corpus);
        assertEquals(1, h.stats().hits(), "同版本同查询命中缓存");
        h.bumpCorpus();
        h.rankCached(1, "beta", q -> corpus);
        assertEquals(1, h.stats().hits(), "版本推进缓存失效重算");
        assertThrows(IllegalArgumentException.class, () -> h.rankCached(0, "beta", q -> corpus),
                "过期版本拒绝");
    }

    @Test
    void portOrchestrationAndGenericLinkage() {
        FuzzPort port = FuzzPort.inMemory();
        List<FuzzMatcher.Candidate> out = port.search("bt", List.of("bat", "bot", "bit"));
        assertEquals(List.of("bat", "bit", "bot"), out.stream().map(FuzzMatcher.Candidate::text).toList(),
                "同分字典序");
        record Doc(long id, String title) {
        }
        List<Doc> docs = List.of(new Doc(1, "gateway logs"), new Doc(2, "gateway metrics"));
        List<Doc> ranked = port.rankOver(docs, Doc::title, "gm");
        assertEquals(2, ranked.get(0).id(), "泛型候选形状只读联动");
        assertEquals(List.of(new FuzzMatcher.Range(0, 1)), port.highlight("g", "gateway"));
    }

    @Test
    void rejectAndBoundaryPaths() {
        FuzzQuery q = new FuzzQuery();
        assertTrue(FuzzQuery.parse("").isEmpty(), "空查询无模式");
        assertTrue(q.matches("", "anything"), "空查询全通过");
        assertThrows(IllegalArgumentException.class, () -> new FuzzMatcher.Range(5, 2));
        FuzzMatcher m = new FuzzMatcher();
        assertTrue(m.match("ba", "ba").isPresent());
        assertTrue(m.match("ab", "ba").isEmpty(), "子序列要求有序，乱序不匹配");
    }
}
