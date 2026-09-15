package cn.chyuan.ai.domain.speech.service;

import java.util.ArrayList;
import java.util.List;

/**
 * VAD 能量门限内核（工单 0379 AU1，whisper VAD 思想简化）。
 * 归一化帧能量（0-1）→ 高/低双门限滞回切分语音/静音 → 语音段区间 [start,end)；
 * 最短语音段（不足丢弃）与最短静音间隙（不足合并）约束。纯函数。
 */
public class VoiceActivityDetector {

    /** 语音段（帧区间，end 不含） */
    public record Segment(int startFrame, int endFrame) {

        public int frames() {
            return endFrame - startFrame;
        }
    }

    private final double highThreshold;
    private final double lowThreshold;
    private final int minSpeechFrames;
    private final int minGapFrames;

    public VoiceActivityDetector(double highThreshold, double lowThreshold, int minSpeechFrames, int minGapFrames) {
        if (highThreshold <= lowThreshold) {
            throw new IllegalArgumentException("高门限必须大于低门限（滞回）");
        }
        if (minSpeechFrames < 1 || minGapFrames < 0) {
            throw new IllegalArgumentException("最短语音段至少 1 帧，最短间隙不可为负");
        }
        this.highThreshold = highThreshold;
        this.lowThreshold = lowThreshold;
        this.minSpeechFrames = minSpeechFrames;
        this.minGapFrames = minGapFrames;
    }

    /**
     * 帧能量序列 → 语音段列表。
     */
    public List<Segment> detect(double[] energy) {
        List<Segment> raw = new ArrayList<>();
        if (energy == null || energy.length == 0) {
            return raw;
        }
        boolean speaking = false;
        int speechStart = -1;
        for (int i = 0; i < energy.length; i++) {
            if (!speaking) {
                if (energy[i] >= highThreshold) {
                    speaking = true;
                    speechStart = i;
                }
            } else if (energy[i] < lowThreshold) {
                raw.add(new Segment(speechStart, i));
                speaking = false;
                speechStart = -1;
            }
        }
        if (speaking) {
            raw.add(new Segment(speechStart, energy.length));
        }
        // 后处理：合并近间隙 + 丢短段
        List<Segment> merged = new ArrayList<>();
        for (Segment segment : raw) {
            if (!merged.isEmpty()) {
                Segment last = merged.get(merged.size() - 1);
                if (segment.startFrame() - last.endFrame() < minGapFrames) {
                    merged.set(merged.size() - 1, new Segment(last.startFrame(), segment.endFrame()));
                    continue;
                }
            }
            merged.add(segment);
        }
        return merged.stream().filter(s -> s.frames() >= minSpeechFrames).toList();
    }
}
