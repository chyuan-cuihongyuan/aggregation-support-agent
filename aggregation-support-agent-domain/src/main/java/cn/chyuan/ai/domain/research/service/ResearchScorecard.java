package cn.chyuan.ai.domain.research.service;

import cn.chyuan.ai.domain.research.model.valobj.OutlineVO;
import cn.chyuan.ai.domain.research.model.valobj.ResearchReportVO;
import cn.chyuan.ai.domain.research.model.valobj.SearchHitVO;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 研究质量记分卡（工单 0354 AR8，沿用 AF09 记分卡先例）。
 * 视角覆盖度/引用率/来源多样性（域名熵）/缺口闭合率/token 成本五项加权，
 * 权重可配置，评级 A/B/C/D。domain 纯函数。
 */
public class ResearchScorecard {

    /** 记分输入 */
    public record Input(double perspectiveCoverage, double citationRate,
                        double domainEntropy, double gapClosureRate, long tokenCost) {
    }

    private final double wCoverage;
    private final double wCitation;
    private final double wDiversity;
    private final double wClosure;

    public ResearchScorecard(double wCoverage, double wCitation, double wDiversity, double wClosure) {
        double sum = wCoverage + wCitation + wDiversity + wClosure;
        if (Math.abs(sum - 1.0) > 1e-9) {
            throw new IllegalArgumentException("权重和必须为 1.0，当前 " + sum);
        }
        this.wCoverage = wCoverage;
        this.wCitation = wCitation;
        this.wDiversity = wDiversity;
        this.wClosure = wClosure;
    }

    /** 记分（token 成本惩罚：超 10000 扣减，0.05 封顶） */
    public Result score(Input input) {
        double costPenalty = Math.min(0.05, input.tokenCost() / 200000.0);
        double total = wCoverage * clamp01(input.perspectiveCoverage())
                + wCitation * clamp01(input.citationRate())
                + wDiversity * clamp01(input.domainEntropy())
                + wClosure * clamp01(input.gapClosureRate())
                - costPenalty;
        return new Result(clamp01(total), grade(total));
    }

    /** 从部件合成输入（覆盖度=有证据视角占比；多样性=域名归一熵） */
    public double perspectiveCoverage(OutlineVO outline, List<SearchHitVO> evidence) {
        if (outline.getSections().isEmpty()) {
            return 0;
        }
        long covered = outline.getSections().stream()
                .filter(section -> section.getQuestions().stream()
                        .anyMatch(q -> evidence.stream()
                                .anyMatch(hit -> SearchRefineLoop.relevance(q, hit) > 0)))
                .count();
        return (double) covered / outline.getSections().size();
    }

    public double domainEntropy(List<SearchHitVO> evidence) {
        if (evidence.isEmpty()) {
            return 0;
        }
        Map<String, Integer> byDomain = new java.util.LinkedHashMap<>();
        for (SearchHitVO hit : evidence) {
            byDomain.merge(SourceScorer.domainOf(hit.getUrl()), 1, Integer::sum);
        }
        double entropy = 0;
        for (Integer count : byDomain.values()) {
            double p = (double) count / evidence.size();
            entropy -= p * Math.log(p);
        }
        double max = Math.log(byDomain.size());
        return max == 0 ? 0 : entropy / max;
    }

    public static String gradeOf(double score) {
        return score >= 0.8 ? "A" : score >= 0.6 ? "B" : score >= 0.4 ? "C" : "D";
    }

    private String grade(double score) {
        return gradeOf(score);
    }

    private double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }

    /** 记分结果 */
    public record Result(double score, String grade) {
    }
}
