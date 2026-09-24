package cn.chyuan.ai.domain.tokenkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 预切分（工单 0741 CJ2，transformers 思想）。
 * whitespace 与标点子集切分/原文偏移区间追踪/空段丢弃。
 */
public final class PreTokenizer {

    /** 词片：文本与归一化文本内的 [start,end) 偏移 */
    public record Span(String text, int start, int end) {
    }

    private final boolean splitPunctuation;

    public PreTokenizer(boolean splitPunctuation) {
        this.splitPunctuation = splitPunctuation;
    }

    /** 切分：空白分段；段内标点单字符成片（可配）；空段丢弃 */
    public List<Span> pretokenize(String text) {
        if (text == null) {
            throw new IllegalArgumentException("输入不可为 null");
        }
        List<Span> spans = new ArrayList<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            if (Character.isWhitespace(text.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            while (i < n && !Character.isWhitespace(text.charAt(i))
                    && !(splitPunctuation && isPunct(text.charAt(i)))) {
                i++;
            }
            if (i == start) {
                // 标点单字符片
                spans.add(new Span(String.valueOf(text.charAt(i)), start, start + 1));
                i++;
                continue;
            }
            spans.add(new Span(text.substring(start, i), start, i));
            if (splitPunctuation && i < n && isPunct(text.charAt(i))) {
                spans.add(new Span(String.valueOf(text.charAt(i)), i, i + 1));
                i++;
            }
        }
        return spans;
    }

    /** 标点子集（ASCII 常用） */
    static boolean isPunct(char c) {
        return "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~".indexOf(c) >= 0;
    }
}
