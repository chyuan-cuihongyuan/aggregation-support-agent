package cn.chyuan.ai.domain.segkernel.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 切分粒度三模式（工单 0529 BK5，jieba 精确/全模式/搜索模式思想）。
 * 精确=覆盖概率最大路径+OOV 片段 HMM；全模式=枚举 DAG 全部成词去重保序；
 * 搜索=精确基础上长词（≥3 字）追加相邻二元细粒度。
 */
public final class SegModes {

    private final MaxProbPath maxProb = new MaxProbPath();
    private final HmmSegmenter hmm = new HmmSegmenter();

    /** 精确模式（useHmm=true 时 OOV 单字游程≥2 应用 HMM 合并） */
    public List<String> exact(TrieDict dict, UserDictOverlay overlay, String sentence, boolean useHmm) {
        List<SegDictSpan> spans = spansOf(maxProb.cut(dict, overlay, sentence), sentence);
        if (!useHmm) {
            return wordsOf(spans, sentence);
        }
        List<int[]> blocks = overlay == null ? List.of() : overlay.blockedRanges(sentence);
        List<HmmSegmenter.Span> hmmSpans = new ArrayList<>();
        List<SegDictSpan> dictSpans = new ArrayList<>();
        int i = 0;
        while (i < spans.size()) {
            SegDictSpan cur = spans.get(i);
            if (cur.length() == 1 && dict.freq(cur.text(sentence)) == 0
                    && !overlay.isUser(cur.text(sentence)) && !inBlock(blocks, cur.start())) {
                int j = i;
                while (j + 1 < spans.size() && spans.get(j + 1).length() == 1
                        && dict.freq(spans.get(j + 1).text(sentence)) == 0
                        && !inBlock(blocks, spans.get(j + 1).start())) {
                    j++;
                }
                if (j > i) {
                    int start = spans.get(i).start();
                    int end = spans.get(j).end();
                    hmmSpans.addAll(hmm.decode(sentence.substring(start, end))
                            .stream()
                            .map(s -> new HmmSegmenter.Span(s.start() + start, s.end() + start))
                            .toList());
                } else {
                    dictSpans.add(cur);
                }
                i = j + 1;
            } else {
                dictSpans.add(cur);
                i++;
            }
        }
        List<SegDictSpan> merged = new ArrayList<>(dictSpans);
        for (HmmSegmenter.Span span : hmmSpans) {
            merged.add(new SegDictSpan(span.start(), span.end()));
        }
        merged.sort((a, b) -> a.start() != b.start() ? Integer.compare(a.start(), b.start())
                : Integer.compare(a.end(), b.end()));
        return wordsOf(merged, sentence);
    }

    /** 全模式：DAG 全部成词（≥2 字）按起点-终点升序去重；单字兜底只在无长词覆盖时保留 */
    public List<String> cutAll(TrieDict dict, UserDictOverlay overlay, String sentence) {
        List<List<Integer>> dag = overlay == null ? dict.dag(sentence)
                : overlay.apply(dict.dag(sentence), sentence);
        Set<String> words = new LinkedHashSet<>();
        int n = sentence.length();
        boolean[] covered = new boolean[n];
        for (int i = 0; i < n; i++) {
            for (int end : dag.get(i)) {
                if (end > i + 1) {
                    words.add(sentence.substring(i, end));
                    for (int k = i; k < end; k++) {
                        covered[k] = true;
                    }
                }
            }
        }
        for (int i = 0; i < n; i++) {
            if (!covered[i]) {
                words.add(sentence.substring(i, i + 1));
            }
        }
        return List.copyOf(words);
    }

    /** 搜索模式：精确词序保留，≥3 字词追加相邻二元（去重保首次出现位） */
    public List<String> cutForSearch(TrieDict dict, UserDictOverlay overlay, String sentence, boolean useHmm) {
        List<String> exact = exact(dict, overlay, sentence, useHmm);
        Set<String> out = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(exact);
        while (!queue.isEmpty()) {
            String word = queue.pollFirst();
            out.add(word);
            if (word.length() >= 3) {
                for (int i = 0; i + 2 <= word.length(); i++) {
                    out.add(word.substring(i, i + 2));
                }
            }
        }
        return List.copyOf(out);
    }

    private boolean inBlock(List<int[]> blocks, int pos) {
        for (int[] range : blocks) {
            if (pos >= range[0] && pos < range[1]) {
                return true;
            }
        }
        return false;
    }

    private List<SegDictSpan> spansOf(List<String> words, String sentence) {
        List<SegDictSpan> spans = new ArrayList<>();
        int cursor = 0;
        for (String word : words) {
            int hit = sentence.indexOf(word, cursor);
            spans.add(new SegDictSpan(hit, hit + word.length()));
            cursor = hit + word.length();
        }
        return spans;
    }

    private List<String> wordsOf(List<SegDictSpan> spans, String sentence) {
        List<String> words = new ArrayList<>();
        for (SegDictSpan span : spans) {
            words.add(sentence.substring(span.start(), span.end()));
        }
        return List.copyOf(words);
    }

    private record SegDictSpan(int start, int end) {
        int length() {
            return end - start;
        }

        String text(String sentence) {
            return sentence.substring(start, end);
        }
    }
}
