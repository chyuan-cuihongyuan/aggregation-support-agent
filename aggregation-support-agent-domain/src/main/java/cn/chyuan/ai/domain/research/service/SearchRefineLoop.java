package cn.chyuan.ai.domain.research.service;

import cn.chyuan.ai.domain.research.model.valobj.OutlineVO;
import cn.chyuan.ai.domain.research.model.valobj.SearchHitVO;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 迭代搜索精炼循环（工单 0348 AR2，gpt-researcher 循环思想）。
 * 按大纲问题逐项搜索（端口）→相关性评分（关键词重叠+分数加权）→
 * 未达标问题识别为缺口 →缺口改写补搜 →轮次预算上限出口。
 * domain 纯函数编排。
 */
public class SearchRefineLoop {

    /** 搜索端口：查询 → 命中列表（异常按空结果） */
    public interface SearchPort {
        List<SearchHitVO> search(String query);
    }

    /** 达标分数线 */
    private final double qualifiedScore;
    private final int maxRounds;

    public SearchRefineLoop(double qualifiedScore, int maxRounds) {
        if (qualifiedScore < 0 || qualifiedScore > 1 || maxRounds <= 0) {
            throw new IllegalArgumentException("达标线应在 [0,1] 且轮次上限为正数");
        }
        this.qualifiedScore = qualifiedScore;
        this.maxRounds = maxRounds;
    }

    /** 执行循环：返回收集证据 + 残留缺口 */
    public Result run(OutlineVO outline, SearchPort searchPort, SourceScorer scorer) {
        if (outline == null || outline.getSections() == null || outline.getSections().isEmpty()) {
            throw new IllegalArgumentException("大纲不能为空");
        }
        if (searchPort == null || scorer == null) {
            throw new IllegalArgumentException("搜索端口与评分器不能为空");
        }
        List<SearchHitVO> evidence = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        Set<String> gaps = new HashSet<>();
        List<String> questions = flatten(outline);
        for (int round = 1; round <= maxRounds; round++) {
            Set<String> currentGaps = new HashSet<>();
            for (String question : round == 1 ? questions : gaps) {
                List<SearchHitVO> hits = safeSearch(searchPort, question);
                List<SearchHitVO> scored = scorer.score(hits, null);
                boolean qualified = false;
                for (SearchHitVO hit : scored) {
                    if (relevance(question, hit) >= qualifiedScore && !hit.getSnippet().isBlank()) {
                        qualified = true;
                        if (seenUrls.add(hit.getUrl())) {
                            evidence.add(hit);
                        }
                    }
                }
                if (!qualified) {
                    currentGaps.add(question);
                }
            }
            if (currentGaps.isEmpty()) {
                return new Result(List.copyOf(evidence), List.of(), round, true);
            }
            gaps.clear();
            gaps.addAll(currentGaps);
        }
        return new Result(List.copyOf(evidence), List.copyOf(gaps), maxRounds, false);
    }

    /** 相关性评分：问题与命中摘要/标题的字符二元组重叠率（0-1） */
    static double relevance(String question, SearchHitVO hit) {
        Set<String> questionGrams = bigrams(question.toLowerCase());
        if (questionGrams.isEmpty()) {
            return 0;
        }
        Set<String> hitGrams = new HashSet<>(bigrams(
                (hit.getTitle() + hit.getSnippet()).toLowerCase()));
        long hitCount = questionGrams.stream().filter(hitGrams::contains).count();
        return (double) hitCount / questionGrams.size();
    }

    private static Set<String> bigrams(String text) {
        Set<String> grams = new HashSet<>();
        for (int i = 0; i + 2 < text.length(); i += 2) {
            grams.add(text.substring(i, Math.min(i + 4, text.length())));
        }
        return grams;
    }

    private List<String> flatten(OutlineVO outline) {
        List<String> questions = new ArrayList<>();
        for (OutlineVO.SectionVO section : outline.getSections()) {
            questions.addAll(section.getQuestions());
        }
        return questions;
    }

    private List<SearchHitVO> safeSearch(SearchPort port, String query) {
        try {
            List<SearchHitVO> hits = port.search(query);
            return hits == null ? List.of() : hits;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** 循环结果：证据集 + 残留缺口 + 实际轮数 + 是否全部闭合 */
    public record Result(List<SearchHitVO> evidence, List<String> remainingGaps,
                         int rounds, boolean gapsClosed) {
    }
}
