package cn.chyuan.ai.domain.searchkernel.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 容错拼写匹配（工单 0398 AW3，meilisearch typo tolerance 规则）。
 * 查询词 → 词表内编辑距离≤阈值候选扩展（阈值按词长分档：短词/中词/长词可配）+ 前缀匹配（首词容错可禁）；
 * 候选带代价排序（距离小优先，同距字典序）。纯函数。
 */
public class TypoToleranceExpander {

    /** 分档阈值 */
    public record Tier(int maxLen, int typos) {
    }

    private final List<Tier> tiers;

    public TypoToleranceExpander(List<Tier> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            throw new IllegalArgumentException("分档阈值不可为空");
        }
        List<Tier> sorted = new ArrayList<>(tiers);
        sorted.sort(Comparator.comparingInt(Tier::maxLen));
        this.tiers = List.copyOf(sorted);
    }

    /** 默认分档：≤4 词 0 容错；5-8 词 1；>8 词 2（meilisearch 缺省惯例） */
    public static TypoToleranceExpander defaults() {
        return new TypoToleranceExpander(List.of(new Tier(4, 0), new Tier(8, 1), new Tier(Integer.MAX_VALUE, 2)));
    }

    /** 候选扩展：距离≤分档阈值，按（距离,字典序）排序 */
    public List<Candidate> expand(String word, List<String> vocabulary, boolean isFirstWord) {
        int budget = isFirstWord ? 0 : tierBudget(word.length());
        List<Candidate> out = new ArrayList<>();
        for (String candidate : vocabulary == null ? List.<String>of() : vocabulary) {
            int distance = levenshtein(word, candidate, budget);
            if (distance <= budget) {
                out.add(new Candidate(candidate, distance));
            }
        }
        out.sort(Comparator.comparingInt(Candidate::distance).thenComparing(Candidate::word));
        return out;
    }

    /** 前缀匹配：词表内以 prefix 开头（前缀本身不容错） */
    public List<String> prefixMatches(String prefix, List<String> vocabulary) {
        List<String> out = new ArrayList<>();
        for (String candidate : vocabulary == null ? List.<String>of() : vocabulary) {
            if (candidate.startsWith(prefix)) {
                out.add(candidate);
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    private int tierBudget(int length) {
        for (Tier tier : tiers) {
            if (length <= tier.maxLen()) {
                return tier.typos();
            }
        }
        return 0;
    }

    /** Levenshtein 带早停（超过 budget 返回 budget+1） */
    static int levenshtein(String a, String b, int budget) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            int rowMin = curr[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, curr[j]);
            }
            if (rowMin > budget) {
                return budget + 1;
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }

    /** 候选 */
    public record Candidate(String word, int distance) {
    }
}
