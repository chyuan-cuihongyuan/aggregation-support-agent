package cn.chyuan.ai.domain.inferkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * top-k/top-p 核采样（工单 0506 BI3，llama.cpp sampler 思想）。
 * top-k 候选截断/top-p 累积概率核采样/截断后重归一化/
 * 随机数端口注入（固定种子确定性可复现）。
 */
public class Sampler {

    /** 随机端口 */
    @FunctionalInterface
    public interface RandomPort {
        double nextDouble();
    }

    /** 候选：token id + 概率 */
    public record Candidate(int tokenId, double probability) {
    }

    private final RandomPort random;

    public Sampler(RandomPort random) {
        this.random = random;
    }

    /** top-k 截断：保留概率前 k 个（并列按 id 升序），重归一化 */
    public List<Candidate> topK(double[] probabilities, int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("k 须 > 0");
        }
        List<Candidate> all = new ArrayList<>();
        for (int i = 0; i < probabilities.length; i++) {
            all.add(new Candidate(i, probabilities[i]));
        }
        all.sort((a, b) -> Double.compare(b.probability(), a.probability()) != 0
                ? Double.compare(b.probability(), a.probability())
                : Integer.compare(a.tokenId(), b.tokenId()));
        List<Candidate> kept = all.subList(0, Math.min(k, all.size()));
        return renormalize(kept);
    }

    /** top-p 核采样：候选按概率降序累积 ≥ p 即截断（至少保留 1 个），重归一化（保留原 token id） */
    public List<Candidate> topP(List<Candidate> sortedCandidates, double p) {
        if (p <= 0 || p > 1) {
            throw new IllegalArgumentException("p 须 (0, 1]");
        }
        List<Candidate> sorted = new ArrayList<>(sortedCandidates);
        sorted.sort((a, b) -> Double.compare(b.probability(), a.probability()));
        List<Candidate> kept = new ArrayList<>();
        double cumulative = 0;
        for (Candidate candidate : sorted) {
            kept.add(candidate);
            cumulative += candidate.probability();
            if (cumulative >= p) {
                break;
            }
        }
        return renormalize(kept);
    }

    /** 从候选分布采样（随机端口注入） */
    public int sample(List<Candidate> candidates) {
        double draw = random.nextDouble();
        double cumulative = 0;
        for (Candidate candidate : candidates) {
            cumulative += candidate.probability();
            if (draw < cumulative) {
                return candidate.tokenId();
            }
        }
        return candidates.get(candidates.size() - 1).tokenId();
    }

    private List<Candidate> renormalize(List<Candidate> kept) {
        double sum = kept.stream().mapToDouble(Candidate::probability).sum();
        if (sum <= 0) {
            throw new IllegalStateException("候选概率和为 0");
        }
        List<Candidate> normalized = new ArrayList<>(kept.size());
        for (Candidate candidate : kept) {
            normalized.add(new Candidate(candidate.tokenId(), candidate.probability() / sum));
        }
        return List.copyOf(normalized);
    }
}
