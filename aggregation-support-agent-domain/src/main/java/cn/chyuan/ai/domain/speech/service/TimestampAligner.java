package cn.chyuan.ai.domain.speech.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 词级时间戳对齐（工单 0382 AU4，whisperX 对齐思想简化）。
 * 词序列 × 段起止时间 → 按词字符长比例分配起止时间戳；可选跳过词（零长占位）；
 * 输出校验单调性（start<=end 且词序时间单调不减），违规拒绝。纯函数。
 */
public class TimestampAligner {

    /** 词时间戳 */
    public record WordTiming(String word, long startMs, long endMs) {
    }

    /**
     * 对齐：skipIndexes 中的词分配零长占位（起点=前词终点）。
     *
     * @throws IllegalArgumentException 段区间非法或结果违反单调性
     */
    public List<WordTiming> align(List<String> words, long segStartMs, long segEndMs, java.util.Set<Integer> skipIndexes) {
        if (segEndMs < segStartMs) {
            throw new IllegalArgumentException("段结束不可早于开始");
        }
        if (words == null || words.isEmpty()) {
            return List.of();
        }
        java.util.Set<Integer> skip = skipIndexes == null ? java.util.Set.of() : skipIndexes;
        long totalWeight = 0;
        for (int i = 0; i < words.size(); i++) {
            totalWeight += skip.contains(i) ? 0 : Math.max(1, words.get(i).length());
        }
        if (totalWeight == 0) {
            throw new IllegalArgumentException("全部词均被跳过，无法对齐");
        }
        List<WordTiming> out = new ArrayList<>(words.size());
        long cursor = segStartMs;
        long duration = segEndMs - segStartMs;
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            long span;
            if (skip.contains(i)) {
                span = 0;
            } else {
                long weight = Math.max(1, word.length());
                // 四舍五入分配，末词兜底到段尾
                span = i == words.size() - 1
                        ? segEndMs - cursor
                        : Math.round((double) weight * duration / totalWeight);
            }
            long end = Math.min(segEndMs, cursor + span);
            out.add(new WordTiming(word, cursor, end));
            cursor = end;
        }
        validate(out);
        return out;
    }

    /** 单调性校验：start<=end 且时间轴单调不减 */
    private void validate(List<WordTiming> timings) {
        long previousEnd = Long.MIN_VALUE;
        for (WordTiming timing : timings) {
            if (timing.startMs() > timing.endMs()) {
                throw new IllegalStateException("词时间戳非法（start>end）: " + timing);
            }
            if (timing.startMs() < previousEnd) {
                throw new IllegalStateException("词时间戳违反单调性: " + timing);
            }
            previousEnd = timing.endMs();
        }
    }
}
