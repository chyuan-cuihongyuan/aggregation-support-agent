package cn.chyuan.ai.domain.tokenkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 截断与填充（工单 0745 CJ6，transformers 思想）。
 * max_len 截断（longest_first/only_first）/padding 侧与目标长度可配/attention mask 生成。
 */
public final class PaddingTrimmer {

    public enum TruncationStrategy {LONGEST_FIRST, ONLY_FIRST}

    public enum PaddingSide {RIGHT, LEFT}

    private final int maxLength;
    private final TruncationStrategy strategy;
    private final PaddingSide side;
    private final String padToken;

    public PaddingTrimmer(int maxLength, TruncationStrategy strategy, PaddingSide side, String padToken) {
        if (maxLength <= 0) {
            throw new IllegalArgumentException("max_len 须为正");
        }
        this.maxLength = maxLength;
        this.strategy = strategy;
        this.side = side;
        this.padToken = padToken;
    }

    /** 单序列截断：超长去尾 */
    public List<String> truncate(List<String> tokens) {
        if (tokens.size() <= maxLength) {
            return tokens;
        }
        return new ArrayList<>(tokens.subList(0, maxLength));
    }

    /** 句对截断：longest_first 交替从较长侧去尾，only_first 只截第一句 */
    public List<List<String>> truncatePair(List<String> first, List<String> second) {
        List<String> a = new ArrayList<>(first);
        List<String> b = new ArrayList<>(second);
        int overflow = a.size() + b.size() - maxLength;
        if (overflow <= 0) {
            return List.of(a, b);
        }
        if (strategy == TruncationStrategy.ONLY_FIRST) {
            if (overflow > a.size()) {
                throw new IllegalArgumentException("only_first 截断不足");
            }
            a = new ArrayList<>(a.subList(0, a.size() - overflow));
            return List.of(a, b);
        }
        while (overflow > 0 && (a.size() > 0 || b.size() > 0)) {
            if (a.size() >= b.size() && a.size() > 0) {
                a.remove(a.size() - 1);
            } else if (b.size() > 0) {
                b.remove(b.size() - 1);
            }
            overflow--;
        }
        return List.of(a, b);
    }

    /** 填充：side 侧补 pad，返回 ids 与 attention mask（1 实 0 填） */
    public Padded pad(List<String> tokens, String padId, Integer targetLength) {
        int target = targetLength != null ? targetLength : maxLength;
        if (tokens.size() > target) {
            throw new IllegalArgumentException("序列超目标长度");
        }
        List<String> ids = new ArrayList<>(tokens);
        List<Integer> mask = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            mask.add(1);
        }
        int padCount = target - tokens.size();
        for (int i = 0; i < padCount; i++) {
            if (side == PaddingSide.RIGHT) {
                ids.add(padId);
                mask.add(0);
            } else {
                ids.add(0, padId);
                mask.add(0, 0);
            }
        }
        return new Padded(ids, mask);
    }

    public record Padded(List<String> ids, List<Integer> attentionMask) {
    }

    public int maxLength() {
        return maxLength;
    }

    public PaddingSide side() {
        return side;
    }

    public String padToken() {
        return padToken;
    }
}
