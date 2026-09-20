package cn.chyuan.ai.domain.textkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 分析器链（工单 0488 BG1，elasticsearch analyzer 思想）。
 * 小写规范化/符号切分/停止词过滤/N-gram 生成（CJK 适配）/token 流位置保留
 * （position 连续递增，offset 保留原文位置）。
 */
public class AnalyzerChain {

    /** 分析产物 token：词元 + 位置 + 原文偏移 */
    public record Token(String text, int position, int startOffset, int endOffset) {
    }

    private static final Set<String> DEFAULT_STOPWORDS = Set.of(
            "the", "a", "an", "and", "or", "of", "to", "in", "is", "are", "was", "for", "on");

    private final Set<String> stopwords;
    private final int ngramMin;
    private final int ngramMax;

    public AnalyzerChain() {
        this(DEFAULT_STOPWORDS, 1, 2);
    }

    public AnalyzerChain(Set<String> stopwords, int ngramMin, int ngramMax) {
        this.stopwords = stopwords == null ? Set.of() : stopwords;
        this.ngramMin = Math.max(1, ngramMin);
        this.ngramMax = Math.max(this.ngramMin, ngramMax);
    }

    /** 全链分析：小写 → 符号切分 → 停止词过滤 → CJK 连续段 N-gram 展开 */
    public List<Token> analyze(String text) {
        List<Token> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        String lowered = text.toLowerCase();
        int position = 0;
        int i = 0;
        while (i < lowered.length()) {
            char c = lowered.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (isSymbol(c)) {
                i++;
                continue;
            }
            int start = i;
            while (i < lowered.length() && !Character.isWhitespace(lowered.charAt(i))
                    && !isSymbol(lowered.charAt(i))) {
                i++;
            }
            String raw = lowered.substring(start, i);
            if (containsCjk(raw)) {
                for (Token gram : ngrams(raw, start, position)) {
                    tokens.add(gram);
                    position++;
                }
            } else if (!stopwords.contains(raw)) {
                tokens.add(new Token(raw, position, start, i));
                position++;
            } else {
                position++;
            }
        }
        return tokens;
    }

    /** 符号判定（切分边界，保留数字与字母） */
    static boolean isSymbol(char c) {
        return !(Character.isLetterOrDigit(c)) && !isCjk(c);
    }

    static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF);
    }

    static boolean containsCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (isCjk(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** N-gram 展开（min..max 长度滑窗，offset 保留） */
    private List<Token> ngrams(String raw, int startOffset, int basePosition) {
        List<Token> grams = new ArrayList<>();
        int position = basePosition;
        for (int len = ngramMin; len <= ngramMax; len++) {
            for (int from = 0; from + len <= raw.length(); from++) {
                grams.add(new Token(raw.substring(from, from + len), position++, startOffset + from,
                        startOffset + from + len));
            }
        }
        return grams;
    }
}
