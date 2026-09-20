package cn.chyuan.ai.domain.textkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 命中高亮（工单 0492 BG5，elasticsearch highlighting 思想）。
 * fragment 窗口切分/命中词偏移标注（<em> 标记）/片段数与长度上限配置。
 */
public class Highlighter {

    /** 高亮片段：文本（含标记）+ 原文起点 */
    public record Fragment(String text, int startOffset) {
    }

    private final int fragmentCount;
    private final int fragmentLength;
    private final String preTag;
    private final String postTag;

    public Highlighter(int fragmentCount, int fragmentLength) {
        this(fragmentCount, fragmentLength, "<em>", "</em>");
    }

    public Highlighter(int fragmentCount, int fragmentLength, String preTag, String postTag) {
        if (fragmentCount <= 0 || fragmentLength <= 0) {
            throw new IllegalArgumentException("片段数与长度须 > 0");
        }
        this.fragmentCount = fragmentCount;
        this.fragmentLength = fragmentLength;
        this.preTag = preTag;
        this.postTag = postTag;
    }

    /** 高亮：命中词（小写集合）在原文的偏移标注，片段数与长度截断 */
    public List<Fragment> highlight(String text, Set<String> hitTerms) {
        String lowered = text.toLowerCase();
        List<Integer> offsets = new ArrayList<>();
        for (String term : hitTerms) {
            String t = term.toLowerCase();
            int from = 0;
            int index;
            while ((index = lowered.indexOf(t, from)) >= 0) {
                offsets.add(index);
                from = index + t.length();
            }
        }
        offsets.sort(Integer::compareTo);
        List<Fragment> fragments = new ArrayList<>();
        int produced = 0;
        int i = 0;
        while (i < offsets.size() && produced < fragmentCount) {
            int hit = offsets.get(i);
            int start = Math.max(0, hit - fragmentLength / 4);
            int end = Math.min(text.length(), start + fragmentLength);
            start = Math.max(0, Math.min(start, hit));
            StringBuilder marked = new StringBuilder(text.substring(start, hit))
                    .append(preTag).append(text.substring(hit, Math.min(text.length(), hit + hitLength(hitTerms, lowered, hit)))
                            ).append(postTag)
                    .append(text.substring(Math.min(text.length(), hit + hitLength(hitTerms, lowered, hit)), end));
            fragments.add(new Fragment(marked.toString(), start));
            produced++;
            while (i < offsets.size() && offsets.get(i) < end) {
                i++;
            }
        }
        return fragments;
    }

    /** 命中词长度（多词命中取起点的匹配词长） */
    private int hitLength(Set<String> hitTerms, String lowered, int hit) {
        int max = 1;
        for (String term : hitTerms) {
            String t = term.toLowerCase();
            if (lowered.startsWith(t, hit)) {
                max = Math.max(max, t.length());
            }
        }
        return max;
    }
}
