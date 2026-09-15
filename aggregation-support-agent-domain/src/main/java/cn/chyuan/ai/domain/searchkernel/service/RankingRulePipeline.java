package cn.chyuan.ai.domain.searchkernel.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 排序规则管道（工单 0399 AW4，meilisearch ranking rules）。
 * 命中文档评分向量（words 命中词数/typo 代价/proximity 词距/attribute 字段权重/position 早现/exactness 短语全等）
 * → 按规则顺序（可配）字典序比较排序；输出每条结果被决胜负的首条规则（可解释）。纯函数。
 */
public class RankingRulePipeline {

    /** 六级规则（顺序即优先级） */
    public enum Rule {
        WORDS, TYPO, PROXIMITY, ATTRIBUTE, POSITION, EXACTNESS
    }

    /** 评分向量 */
    public record Score(String docId, int words, int typoCost, int proximity,
                        int attribute, int position, int exactness) {
    }

    /** 排序结果（decidedBy=与排序比较中决定其位置的首条规则说明） */
    public record Ranked(Score score, String decidedBy) {
    }

    private final List<Rule> rules;

    public RankingRulePipeline(List<Rule> rules) {
        this.rules = rules == null || rules.isEmpty() ? List.of(Rule.values()) : List.copyOf(rules);
    }

    /**
     * 排序：规则字典序逐级比较；decidedBy 记录该结果与前一名分出高下的首条规则（第一名记其首规则）。
     */
    public List<Ranked> rank(List<Score> scores) {
        List<Score> sorted = new ArrayList<>(scores == null ? List.of() : scores);
        sorted.sort((a, b) -> compare(a, b));
        List<Ranked> out = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            String decidedBy;
            if (i == 0) {
                decidedBy = rules.get(0).name();
            } else {
                decidedBy = firstDecidingRule(sorted.get(i - 1), sorted.get(i));
            }
            out.add(new Ranked(sorted.get(i), decidedBy));
        }
        return out;
    }

    /** 字典序比较：规则顺序内逐级（WORDS/EXACTNESS 大者优，TYPO/PROXIMITY/ATTRIBUTE/POSITION 代价小者优）；负值=前参更优 */
    int compare(Score a, Score b) {
        for (Rule rule : rules) {
            int cmp = switch (rule) {
                case WORDS -> Integer.compare(b.words(), a.words());
                case TYPO -> Integer.compare(a.typoCost(), b.typoCost());
                case PROXIMITY -> Integer.compare(a.proximity(), b.proximity());
                case ATTRIBUTE -> Integer.compare(a.attribute(), b.attribute());
                case POSITION -> Integer.compare(a.position(), b.position());
                case EXACTNESS -> Integer.compare(b.exactness(), a.exactness());
            };
            if (cmp != 0) {
                return cmp;
            }
        }
        return a.docId().compareTo(b.docId());
    }

    /** 首条决出高下的规则名（逐规则独立比较：负值=胜者在该规则占优） */
    private String firstDecidingRule(Score winner, Score loser) {
        for (Rule rule : rules) {
            int cmp = switch (rule) {
                case WORDS -> Integer.compare(loser.words(), winner.words());
                case TYPO -> Integer.compare(winner.typoCost(), loser.typoCost());
                case PROXIMITY -> Integer.compare(winner.proximity(), loser.proximity());
                case ATTRIBUTE -> Integer.compare(winner.attribute(), loser.attribute());
                case POSITION -> Integer.compare(winner.position(), loser.position());
                case EXACTNESS -> Integer.compare(loser.exactness(), winner.exactness());
            };
            if (cmp != 0) {
                return rule.name();
            }
        }
        return rules.get(0).name();
    }
}
