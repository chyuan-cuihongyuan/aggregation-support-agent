package cn.chyuan.ai.domain.docintel.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 多源 OCR 框融合（工单 0390 AV4）。
 * 多来源文本框 → 同位框 IoU≥阈值去重（NMS 按置信度保留序）→ 文本融合（HIGHEST 取胜者 / WEIGHTED_VOTE 簇内加权投票）。
 * 输出统一带来源与贡献者标注。纯函数。
 */
public class OcrBoxFuser {

    /** OCR 文本框 */
    public record Box(String text, double confidence, String source, double x, double y, double width, double height) {
    }

    /** 融合策略 */
    public enum Strategy {
        HIGHEST, WEIGHTED_VOTE
    }

    /** 融合结果框（contributors=参与簇的原来源清单） */
    public record FusedBox(String text, double confidence, String source,
                           double x, double y, double width, double height, List<String> contributors) {
    }

    private final double iouThreshold;
    private final Strategy strategy;

    public OcrBoxFuser(double iouThreshold, Strategy strategy) {
        if (iouThreshold <= 0 || iouThreshold > 1) {
            throw new IllegalArgumentException("IoU 阈值须在 (0,1]");
        }
        this.iouThreshold = iouThreshold;
        this.strategy = strategy;
    }

    /**
     * 融合：按置信度降序 NMS（与已保留框 IoU≥阈值归入其簇），簇内按策略产出文本。
     */
    public List<FusedBox> fuse(List<Box> boxes) {
        if (boxes == null || boxes.isEmpty()) {
            return List.of();
        }
        List<Box> sorted = new ArrayList<>(boxes);
        sorted.sort(Comparator.comparingDouble(Box::confidence).reversed());
        List<List<Box>> clusters = new ArrayList<>();
        for (Box box : sorted) {
            boolean assigned = false;
            for (List<Box> cluster : clusters) {
                for (Box kept : cluster) {
                    if (iou(box, kept) >= iouThreshold) {
                        cluster.add(box);
                        assigned = true;
                        break;
                    }
                }
                if (assigned) {
                    break;
                }
            }
            if (!assigned) {
                List<Box> cluster = new ArrayList<>();
                cluster.add(box);
                clusters.add(cluster);
            }
        }
        List<FusedBox> out = new ArrayList<>();
        for (List<Box> cluster : clusters) {
            Box winner = cluster.get(0); // 已按置信度降序
            List<String> contributors = cluster.stream().map(Box::source).distinct().toList();
            String text = winner.text();
            if (strategy == Strategy.WEIGHTED_VOTE && cluster.size() > 1) {
                text = voteText(cluster);
            }
            out.add(new FusedBox(text, winner.confidence(), winner.source(),
                    winner.x(), winner.y(), winner.width(), winner.height(), contributors));
        }
        out.sort(Comparator.comparingDouble(FusedBox::confidence).reversed());
        return out;
    }

    /** 加权投票：相同文本置信度求和最高者 */
    private String voteText(List<Box> cluster) {
        var sums = new java.util.LinkedHashMap<String, Double>();
        for (Box box : cluster) {
            sums.merge(box.text(), box.confidence(), Double::sum);
        }
        String best = cluster.get(0).text();
        double bestScore = -1;
        for (var entry : sums.entrySet()) {
            if (entry.getValue() > bestScore) {
                bestScore = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }

    /** IoU（交并比） */
    static double iou(Box a, Box b) {
        double ix = Math.max(0, Math.min(a.x() + a.width(), b.x() + b.width()) - Math.max(a.x(), b.x()));
        double iy = Math.max(0, Math.min(a.y() + a.height(), b.y() + b.height()) - Math.max(a.y(), b.y()));
        double inter = ix * iy;
        double union = a.width() * a.height() + b.width() * b.height() - inter;
        return union <= 0 ? 0 : inter / union;
    }
}
