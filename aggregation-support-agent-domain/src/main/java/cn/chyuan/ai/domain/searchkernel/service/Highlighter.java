package cn.chyuan.ai.domain.searchkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 高亮定位（工单 0403 AW8）。
 * 字段文本 × 命中词 → 首命中词级边界定位（子串不误伤）+ 前后各 N 字符截断片段（省略号）；
 * 多命中取最早。纯函数。
 */
public class Highlighter {

    /** 片段：文本 + 命中相对位置（无命中 -1） */
    public record Snippet(String text, int hitStart) {
    }

    private final int contextChars;
    private final SearchTokenizer tokenizer = new SearchTokenizer();

    public Highlighter(int contextChars) {
        if (contextChars < 0) {
            throw new IllegalArgumentException("上下文字符数不可为负");
        }
        this.contextChars = contextChars;
    }

    /**
     * 高亮：返回截断片段（命中词前后各 contextChars，越界省略号）。无命中返回头部截断（hitStart=-1）。
     */
    public Snippet highlight(String text, List<String> hitTerms) {
        if (text == null || text.isEmpty()) {
            return new Snippet("", -1);
        }
        int hitStart = findFirstHit(text, hitTerms == null ? List.of() : hitTerms);
        int hitEnd = hitStart >= 0 ? hitStart + hitLength(text, hitStart, hitTerms) : 0;
        int from = Math.max(0, hitStart - contextChars);
        int to = Math.min(text.length(), (hitStart >= 0 ? hitEnd : Math.min(text.length(), contextChars)) + contextChars);
        String snippet = text.substring(from, to);
        String prefix = from > 0 ? "…" : "";
        String suffix = to < text.length() ? "…" : "";
        return new Snippet(prefix + snippet + suffix, hitStart < 0 ? -1 : hitStart - from);
    }

    /** 首个词级命中起点（子串不误伤：命中两侧须为非字母数字 CJK 边界） */
    private int findFirstHit(String text, List<String> terms) {
        int best = -1;
        for (String term : terms) {
            List<String> termTokens = tokenizer.tokenize(term);
            if (termTokens.size() != 1) {
                // 短语/多词命中：退化为子串查找
                int at = text.indexOf(term);
                if (at >= 0 && (best < 0 || at < best)) {
                    best = at;
                }
                continue;
            }
            String needle = termTokens.get(0);
            int from = 0;
            while (true) {
                int at = text.indexOf(needle, from);
                if (at < 0) {
                    break;
                }
                if (isBoundary(text, at) && isBoundary(text, at + needle.length())) {
                    if (best < 0 || at < best) {
                        best = at;
                    }
                    break;
                }
                from = at + 1;
            }
        }
        return best;
    }

    /** 命中长度：词级命中为词长，子串命中为该词后续连续字母数字段长 */
    private int hitLength(String text, int start, List<String> terms) {
        int end = start;
        while (end < text.length()) {
            char ch = text.charAt(end);
            boolean normalized = ch >= 0xFF01 && ch <= 0xFF5E;
            if (Character.isLetterOrDigit(ch) || SearchTokenizer.isCjk(ch) || normalized) {
                end++;
            } else {
                break;
            }
        }
        return Math.max(1, end - start);
    }

    /** 词边界：位置前/后字符为边界（串首尾、非字母数字 CJK） */
    private boolean isBoundary(String text, int pos) {
        if (pos <= 0 || pos >= text.length()) {
            return true;
        }
        char ch = text.charAt(pos);
        return !(Character.isLetterOrDigit(ch) || SearchTokenizer.isCjk(ch));
    }
}
