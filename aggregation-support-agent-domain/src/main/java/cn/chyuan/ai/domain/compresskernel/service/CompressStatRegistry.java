package cn.chyuan.ai.domain.compresskernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 压缩统计登记（工单 0592 BR7，聚合第 34 表 compress_stat 的域侧形态）。
 * 场景/级别/原始字节/压缩字节/比率/耗时毫秒/样本时间/备注；
 * 追加即登记（幂等去重不做：按样本时间区分）+按比率排序查询。
 */
public final class CompressStatRegistry {

    /** 统计行（与 compress_stat 表字段对齐） */
    public record Stat(String scene, int level, long rawBytes, long compressedBytes,
                       double ratio, long costMs, long sampleAt, String remark) {
    }

    public static final String SOURCE_LEVEL = "LEVEL";
    public static final String SOURCE_ADAPTIVE = "ADAPTIVE";

    private final List<Stat> rows = new ArrayList<>();

    /** 登记一行压缩统计（非法维度拒绝） */
    public synchronized void record(String scene, int level, long rawBytes, long compressedBytes,
                                    long costMs, long sampleAt, String remark) {
        if (scene == null || scene.isBlank()) {
            throw new IllegalArgumentException("场景不得为空");
        }
        CompressionLevels.of(level);
        if (rawBytes < 0 || compressedBytes < 0 || costMs < 0) {
            throw new IllegalArgumentException("字节数与耗时不得为负");
        }
        double ratio = rawBytes == 0 ? 0d : (double) compressedBytes / rawBytes;
        rows.add(new Stat(scene, level, rawBytes, compressedBytes, ratio, costMs, sampleAt,
                remark == null ? "" : remark));
    }

    /** 全量统计（按登记序） */
    public synchronized List<Stat> all() {
        return List.copyOf(rows);
    }

    /** 按比率升序（压缩效果最好在前）的 topN */
    public synchronized List<Stat> bestRatio(int topN) {
        if (topN <= 0) {
            throw new IllegalArgumentException("topN 须为正");
        }
        List<Stat> sorted = new ArrayList<>(rows);
        sorted.sort((a, b) -> {
            int byRatio = Double.compare(a.ratio(), b.ratio());
            if (byRatio != 0) {
                return byRatio;
            }
            return Long.compare(a.sampleAt(), b.sampleAt());
        });
        return sorted.subList(0, Math.min(topN, sorted.size())).stream().toList();
    }

    /** 场景聚合：平均比率 */
    public synchronized double avgRatio(String scene) {
        double sum = 0;
        int count = 0;
        for (Stat row : rows) {
            if (row.scene().equals(scene)) {
                sum += row.ratio();
                count++;
            }
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    /** 行数 */
    public synchronized int size() {
        return rows.size();
    }
}
