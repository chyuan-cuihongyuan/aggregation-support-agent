package cn.chyuan.ai.domain.inferkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 贪心与束搜索（工单 0509 BI6）。
 * greedy 逐步取 argmax/beam search 束宽保留/长度惩罚归一
 * （累计 logprob ÷ 长度^α）/同输入确定性输出（无随机源）。
 */
public class Decoder {

    /** 候选序列：token 列表 + 累计 logprob */
    public static final class Sequence implements Comparable<Sequence> {
        final List<Integer> tokens;
        final double cumulativeLogProb;

        Sequence(List<Integer> tokens, double cumulativeLogProb) {
            this.tokens = List.copyOf(tokens);
            this.cumulativeLogProb = cumulativeLogProb;
        }

        Sequence extend(int token, double logProb) {
            List<Integer> next = new ArrayList<>(tokens);
            next.add(token);
            return new Sequence(next, cumulativeLogProb + logProb);
        }

        @Override
        public int compareTo(Sequence other) {
            return Double.compare(other.cumulativeLogProb, this.cumulativeLogProb);
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof Sequence other
                    && tokens.equals(other.tokens)
                    && Double.compare(cumulativeLogProb, other.cumulativeLogProb) == 0;
        }

        @Override
        public int hashCode() {
            return tokens.hashCode() * 31 + Double.hashCode(cumulativeLogProb);
        }
    }

    /** 长度惩罚归一分（α：0 = 不惩罚，1 = 平均 logprob） */
    public static double normalizedScore(Sequence sequence, double alpha) {
        if (sequence.tokens.isEmpty()) {
            return sequence.cumulativeLogProb;
        }
        return sequence.cumulativeLogProb / Math.pow(sequence.tokens.size(), alpha);
    }

    /** greedy：每步 argmax（logprob 表按步给出） */
    public static List<Integer> greedy(List<double[]> stepLogProbs) {
        List<Integer> tokens = new ArrayList<>();
        for (double[] logProbs : stepLogProbs) {
            tokens.add(LogitsOps.argmax(logProbs));
        }
        return tokens;
    }

    /** beam search：每步扩展全部候选保留 top 束宽（确定性：同分按 token id 升序） */
    public static List<Sequence> beam(List<double[]> stepLogProbs, int beamWidth) {
        if (beamWidth <= 0) {
            throw new IllegalArgumentException("束宽须 > 0");
        }
        List<Sequence> beams = new ArrayList<>(List.of(new Sequence(List.of(), 0.0)));
        for (double[] logProbs : stepLogProbs) {
            List<Sequence> expanded = new ArrayList<>();
            for (Sequence sequence : beams) {
                for (int token = 0; token < logProbs.length; token++) {
                    expanded.add(sequence.extend(token, logProbs[token]));
                }
            }
            expanded.sort(Sequence::compareTo);
            beams = new ArrayList<>(expanded.subList(0, Math.min(beamWidth, expanded.size())));
        }
        return beams;
    }
}
