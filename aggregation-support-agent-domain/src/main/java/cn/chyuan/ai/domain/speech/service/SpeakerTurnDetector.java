package cn.chyuan.ai.domain.speech.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 说话人轮转检测（工单 0383 AU5，diarization 思想简化）。
 * 帧能量 + 基频（f0）序列 → 显著变化点（能量阶跃比×f0 跳变联合判定）→ 说话人段切换标签；
 * 最短说话段约束防抖（近距变化点取更强者）。变化点留痕。纯函数。
 */
public class SpeakerTurnDetector {

    /** 说话人段 */
    public record Turn(int startFrame, int endFrame, String speaker) {

        public int frames() {
            return endFrame - startFrame;
        }
    }

    /** 检测结果：段列表 + 接受的变化点帧号 */
    public record Detection(List<Turn> turns, List<Integer> changePoints) {
    }

    private final double energyJumpRatio;
    private final double f0JumpHz;
    private final int minTurnFrames;

    public SpeakerTurnDetector(double energyJumpRatio, double f0JumpHz, int minTurnFrames) {
        if (energyJumpRatio < 1.0) {
            throw new IllegalArgumentException("能量阶跃比至少 1.0");
        }
        if (f0JumpHz < 0) {
            throw new IllegalArgumentException("基频跳变阈值不可为负");
        }
        if (minTurnFrames < 1) {
            throw new IllegalArgumentException("最短说话段至少 1 帧");
        }
        this.energyJumpRatio = energyJumpRatio;
        this.f0JumpHz = f0JumpHz;
        this.minTurnFrames = minTurnFrames;
    }

    /**
     * 检测：energy/f0 等长序列 → 说话人段（SPEAKER_00/01 交替）。
     */
    public Detection detect(double[] energy, double[] f0) {
        if (energy == null || f0 == null || energy.length != f0.length) {
            throw new IllegalArgumentException("能量与基频序列须等长非空");
        }
        // 候选变化点：能量阶跃（上跳或跌变取 max/min 比）或基频跳变（记录强度=二者归一较大值）
        List<int[]> candidates = new ArrayList<>(); // [frame, strengthPermille]
        for (int i = 1; i < energy.length; i++) {
            boolean energyJump = energy[i - 1] > 0 && energy[i] > 0
                    && Math.max(energy[i], energy[i - 1]) / Math.min(energy[i], energy[i - 1]) >= energyJumpRatio;
            boolean f0Jump = Math.abs(f0[i] - f0[i - 1]) >= f0JumpHz;
            if (energyJump || f0Jump) {
                int strength = (int) Math.round(Math.max(
                        energy[i - 1] > 0 && energy[i] > 0
                                ? Math.max(energy[i], energy[i - 1]) / Math.min(energy[i], energy[i - 1]) : 1.0,
                        f0JumpHz > 0 ? Math.abs(f0[i] - f0[i - 1]) / f0JumpHz : 1.0) * 1000);
                candidates.add(new int[]{i, strength});
            }
        }
        // 防抖：近距（<minTurnFrames）变化点仅保留强度最大者
        List<Integer> accepted = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            int frame = candidates.get(i)[0];
            int strength = candidates.get(i)[1];
            if (!accepted.isEmpty() && frame - accepted.get(accepted.size() - 1) < minTurnFrames) {
                // 与上一接受点过近：当前更强则替换
                int lastAccepted = accepted.get(accepted.size() - 1);
                int lastStrength = strengthOf(candidates, lastAccepted);
                if (strength > lastStrength) {
                    accepted.set(accepted.size() - 1, frame);
                }
                continue;
            }
            accepted.add(frame);
        }
        // 段装配 + 标签交替
        List<Turn> turns = new ArrayList<>();
        int start = 0;
        String speaker = "SPEAKER_00";
        for (int point : accepted) {
            if (point - start >= minTurnFrames) {
                turns.add(new Turn(start, point, speaker));
                speaker = speaker.equals("SPEAKER_00") ? "SPEAKER_01" : "SPEAKER_00";
                start = point;
            }
        }
        if (start < energy.length) {
            turns.add(new Turn(start, energy.length, speaker));
        }
        return new Detection(turns, accepted);
    }

    private int strengthOf(List<int[]> candidates, int frame) {
        for (int[] candidate : candidates) {
            if (candidate[0] == frame) {
                return candidate[1];
            }
        }
        return 0;
    }
}
