package cn.chyuan.ai.domain.fuzzkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询历史与增量缓存（工单 0775 CM7，fzf 思想）。
 * 历史登记与频次/语料版本驱动的缓存失效/重复查询命中统计。
 */
public final class FuzzHistory {

    /** 历史条目：查询文本与累计次数 */
    public record Entry(String query, int count) {
    }

    /** 缓存统计：登记条数/命中次数 */
    public record Stats(int entries, int hits) {
    }

    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final Map<String, List<FuzzMatcher.Candidate>> cache = new LinkedHashMap<>();
    private long corpusVersion = 0;
    private int hits = 0;

    /** 登记一次查询，返回累计次数（频次递增） */
    public int record(String query) {
        int next = counts.merge(query, 1, Integer::sum);
        return next;
    }

    /** 历史按频次降序（平票按登记序） */
    public List<Entry> history() {
        List<Entry> entries = new ArrayList<>();
        counts.forEach((q, c) -> entries.add(new Entry(q, c)));
        entries.sort((a, b) -> Integer.compare(b.count(), a.count()));
        return entries;
    }

    /** 语料版本推进：缓存全量失效（前缀增量口径——版本变即失效，保正确性） */
    public void bumpCorpus() {
        corpusVersion++;
        cache.clear();
    }

    /** 带缓存的排序：同版本同查询命中缓存，版本推进则失效重建（版本回退拒绝） */
    public List<FuzzMatcher.Candidate> rankCached(long version, String query,
                                                  java.util.function.Function<String, List<String>> corpusSupplier) {
        if (version < corpusVersion) {
            throw new IllegalArgumentException("过期语料版本: " + version);
        }
        if (version > corpusVersion) {
            corpusVersion = version;
            cache.clear();
        }
        List<FuzzMatcher.Candidate> cached = cache.get(query);
        if (cached != null) {
            hits++;
            return cached;
        }
        List<FuzzMatcher.Candidate> ranked = new FuzzMatcher().rank(query, corpusSupplier.apply(query));
        cache.put(query, ranked);
        return ranked;
    }

    public Stats stats() {
        return new Stats(cache.size(), hits);
    }
}
