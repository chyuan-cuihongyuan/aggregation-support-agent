package cn.chyuan.ai.domain.compresskernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * LZ77 滑动窗口匹配（工单 0586 BR1，zstd 滑窗匹配思想）。
 * 哈希链（3 字节指纹→最近位置链）窗口搜索/最长匹配输出
 * （literal/offset/length 三元组）/最小匹配长度阈值/
 * 窗口与偏移越界拒绝。
 */
public final class Lz77Matcher {

    /** 记号：字面字节 */
    public record Literal(int value) implements Token {
    }

    /** 记号：回指匹配（offset 向后偏移，length 拷贝长度） */
    public record Match(int offset, int length) implements Token {
    }

    /** 记号封闭接口 */
    public sealed interface Token permits Literal, Match {
    }

    public static final int MAX_MATCH_LENGTH = 258;
    public static final int HASH_LENGTH = 3;

    private Lz77Matcher() {
    }

    /**
     * 滑窗匹配：prefix 为虚拟历史（预设词典），input 为待压缩数据；
     * 匹配可回指 prefix 内。windowSize 搜索窗、maxChain 哈希链深、
     * lazy 懒惰匹配（下一位置更长则先吐字面量）。
     */
    public static List<Token> match(byte[] input, byte[] prefix, int windowSize, int minMatch, int maxChain, boolean lazy) {
        if (input == null || prefix == null) {
            throw new IllegalArgumentException("输入与词典不得为 null");
        }
        if (windowSize <= 0 || maxChain <= 0 || minMatch < HASH_LENGTH) {
            throw new IllegalArgumentException("窗口/链深须为正，最小匹配至少 " + HASH_LENGTH);
        }
        if (prefix.length > windowSize) {
            throw new IllegalArgumentException("词典长度超过窗口大小：词典将不可达");
        }
        List<Token> tokens = new ArrayList<>();
        byte[] buf = new byte[prefix.length + input.length];
        System.arraycopy(prefix, 0, buf, 0, prefix.length);
        System.arraycopy(input, 0, buf, prefix.length, input.length);
        int base = prefix.length;
        int total = buf.length;
        int[] head = new int[windowSize];
        java.util.Arrays.fill(head, -1);
        int[] prev = new int[total];
        java.util.Arrays.fill(prev, -1);
        int inserted = Math.max(0, base - windowSize);
        int i = 0;
        while (i < input.length) {
            int p = base + i;
            inserted = insertUpTo(buf, inserted, p, head, prev, windowSize);
            Match best = findBest(buf, p, head, prev, windowSize, maxChain, minMatch, input.length - i);
            if (best != null && lazy && p + 1 < total) {
                int insertedNext = insertUpTo(buf, inserted, p + 1, head, prev, windowSize);
                Match next = findBest(buf, p + 1, head, prev, windowSize, maxChain, minMatch, input.length - i - 1);
                if (next != null && next.length() > best.length()) {
                    tokens.add(new Literal(buf[p] & 0xFF));
                    i++;
                    continue;
                }
                inserted = insertedNext;
            }
            if (best != null) {
                tokens.add(best);
                i += best.length();
            } else {
                tokens.add(new Literal(buf[p] & 0xFF));
                i++;
            }
        }
        return List.copyOf(tokens);
    }

    /** 把 [from, to) 内可哈希位置插入哈希链（链头即最近位置） */
    private static int insertUpTo(byte[] buf, int from, int to, int[] head, int[] prev, int windowSize) {
        for (int pos = from; pos < to; pos++) {
            if (pos + HASH_LENGTH <= buf.length) {
                int h = hashAt(buf, pos) % windowSize;
                prev[pos] = head[h];
                head[h] = pos;
            }
        }
        return Math.max(from, to);
    }

    /** 链上回溯搜索最长匹配（链深与窗口双限） */
    private static Match findBest(byte[] buf, int p, int[] head, int[] prev, int windowSize,
                                  int maxChain, int minMatch, int remaining) {
        if (p + HASH_LENGTH > buf.length || remaining < minMatch) {
            return null;
        }
        int limit = Math.max(0, p - windowSize);
        int candidate = head[hashAt(buf, p) % windowSize];
        int chain = 0;
        Match best = null;
        int maxLen = Math.min(MAX_MATCH_LENGTH, remaining);
        while (candidate >= 0 && candidate >= limit && chain < maxChain) {
            if (candidate < p) {
                int len = 0;
                int max = Math.min(maxLen, buf.length - p);
                while (len < max && buf[candidate + len] == buf[p + len]) {
                    len++;
                }
                if (len >= minMatch && (best == null || len > best.length())) {
                    best = new Match(p - candidate, len);
                    maxLen = len;
                }
                if (len >= maxLen) {
                    break;
                }
            }
            candidate = prev[candidate];
            chain++;
        }
        return best;
    }

    private static int hashAt(byte[] buf, int pos) {
        return ((buf[pos] & 0xFF) << 16) | ((buf[pos + 1] & 0xFF) << 8) | (buf[pos + 2] & 0xFF);
    }
}
