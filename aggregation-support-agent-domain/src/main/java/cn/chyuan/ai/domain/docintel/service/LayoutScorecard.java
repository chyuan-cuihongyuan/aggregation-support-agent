package cn.chyuan.ai.domain.docintel.service;

import cn.chyuan.ai.domain.docintel.model.valobj.LayoutBlockVO;

import java.util.ArrayList;
import java.util.List;

/**
 * 版面质量记分（工单 0394 AV8，沿 AF09 记分卡先例）。
 * 四指标：页面元素覆盖率 / 框重叠率（惩罚）/ 阅读顺序单调率 / 表格结构完整度 → 加权记分 + 评级（A/B/C）+ 短板说明。
 * 权重可配。纯函数。
 */
public class LayoutScorecard {

    /** 记分结果 */
    public record Score(double coverage, double overlapRate, double monotonicRate, double tableCompleteness,
                        double score, String grade, List<String> weaknesses) {
    }

    private final double wCoverage;
    private final double wOverlap;
    private final double wMonotonic;
    private final double wTable;

    public LayoutScorecard(double wCoverage, double wOverlap, double wMonotonic, double wTable) {
        double total = wCoverage + wOverlap + wMonotonic + wTable;
        if (total <= 0) {
            throw new IllegalArgumentException("权重之和必须为正");
        }
        this.wCoverage = wCoverage / total;
        this.wOverlap = wOverlap / total;
        this.wMonotonic = wMonotonic / total;
        this.wTable = wTable / total;
    }

    public static LayoutScorecard defaults() {
        return new LayoutScorecard(0.3, 0.25, 0.25, 0.2);
    }

    /**
     * 记分。
     *
     * @param ordered        阅读顺序排序后的叶元素
     * @param pageWidth      页宽
     * @param pageHeight     页高
     * @param tableCount     表格块数量
     * @param tableComplete  结构完整表格数量
     */
    public Score score(List<LayoutBlockVO> ordered, double pageWidth, double pageHeight,
                       int tableCount, int tableComplete) {
        double pageArea = pageWidth * pageHeight;
        // 覆盖率：元素面积并近似（用总面积，重叠部分在重叠率中惩罚）
        double blockArea = 0;
        double overlapArea = 0;
        for (int i = 0; i < ordered.size(); i++) {
            LayoutBlockVO block = ordered.get(i);
            blockArea += block.getWidth() * block.getHeight();
            for (int j = i + 1; j < ordered.size(); j++) {
                overlapArea += intersectionArea(block, ordered.get(j));
            }
        }
        double coverage = pageArea <= 0 ? 0 : Math.min(1.0, blockArea / pageArea);
        double overlapRate = blockArea <= 0 ? 0 : Math.min(1.0, overlapArea / blockArea);
        // 单调率：阅读顺序相邻对 y 不减（或同栏 x 升）的比例
        double monotonic = monotonicRate(ordered);
        double tableScore = tableCount == 0 ? 1.0 : Math.min(1.0, (double) tableComplete / tableCount);
        double score = round(wCoverage * coverage + wOverlap * (1 - overlapRate)
                + wMonotonic * monotonic + wTable * tableScore);
        String grade = score >= 0.85 ? "A" : score >= 0.7 ? "B" : "C";
        List<String> weaknesses = new ArrayList<>();
        if (coverage < 0.3) {
            weaknesses.add("覆盖率低（" + round(coverage) + "）");
        }
        if (overlapRate > 0.15) {
            weaknesses.add("框重叠偏高（" + round(overlapRate) + "）");
        }
        if (monotonic < 0.8) {
            weaknesses.add("阅读顺序单调率低（" + round(monotonic) + "）");
        }
        if (tableCount > 0 && tableScore < 1.0) {
            weaknesses.add("表格结构不完整（" + tableComplete + "/" + tableCount + "）");
        }
        return new Score(round(coverage), round(overlapRate), round(monotonic), round(tableScore),
                score, grade, weaknesses);
    }

    /** 单调率：相邻元素对 y 不减的比例 */
    private double monotonicRate(List<LayoutBlockVO> ordered) {
        if (ordered.size() < 2) {
            return 1.0;
        }
        int ok = 0;
        for (int i = 1; i < ordered.size(); i++) {
            if (ordered.get(i).getY() >= ordered.get(i - 1).getY() - 1e-9) {
                ok++;
            }
        }
        return (double) ok / (ordered.size() - 1);
    }

    private double intersectionArea(LayoutBlockVO a, LayoutBlockVO b) {
        double ix = Math.max(0, Math.min(a.getX() + a.getWidth(), b.getX() + b.getWidth()) - Math.max(a.getX(), b.getX()));
        double iy = Math.max(0, Math.min(a.getY() + a.getHeight(), b.getY() + b.getHeight()) - Math.max(a.getY(), b.getY()));
        return ix * iy;
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}
