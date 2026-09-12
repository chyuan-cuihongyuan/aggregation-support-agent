package cn.chyuan.ai.domain.rag.service.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 句子窗口检索装配器纯函数（工单 0229 AE2，借鉴 LlamaIndex sentence-window）—
 * 文本按句切分（复用中英分句口径：。！？.!? 分隔，引号随句），命中句回取前后
 * windowSize 句装配为上下文窗口；开头/结尾越界自然截断。
 *
 * @author chyuan
 */
public final class SentenceWindowAssembler {

    private final int windowSize;

    public SentenceWindowAssembler(int windowSize) {
        this.windowSize = Math.max(0, windowSize);
    }

    public SentenceWindowAssembler() {
        this(2);
    }

    /** 按句切分（保序去空行；缩写小数尽量不误切的保守口径：句末标点后必须跟空白或结尾） */
    public static List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return sentences;
        }
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            boolean terminator = c == '。' || c == '！' || c == '？' || c == '；'
                    || ((c == '.' || c == '!' || c == '?') && (i + 1 == text.length()
                    || Character.isWhitespace(text.charAt(i + 1))));
            if (terminator) {
                String sentence = current.toString().trim();
                if (!sentence.isEmpty()) {
                    sentences.add(sentence);
                }
                current.setLength(0);
            }
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            sentences.add(tail);
        }
        return sentences;
    }

    /**
     * 窗口装配：split 为全文句列表，hitIndexes 为命中句下标；
     * 返回按原文句序合并去重的窗口文本列表（每个命中一个窗口，窗口间去重）。
     */
    public List<String> assemble(List<String> split, List<Integer> hitIndexes) {
        List<String> windows = new ArrayList<>();
        Set<Integer> seenSentences = new LinkedHashSet<>();
        if (split == null || hitIndexes == null) {
            return windows;
        }
        for (int hit : hitIndexes) {
            if (hit < 0 || hit >= split.size()) {
                continue;
            }
            int from = Math.max(0, hit - windowSize);
            int to = Math.min(split.size() - 1, hit + windowSize);
            LinkedHashSet<Integer> window = new LinkedHashSet<>();
            for (int i = from; i <= to; i++) {
                window.add(i);
            }
            // 窗口与已见句完全重复则跳过（去重）
            if (seenSentences.containsAll(window) && !windows.isEmpty()) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            for (int i : window) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(split.get(i));
                seenSentences.add(i);
            }
            windows.add(sb.toString());
        }
        return windows;
    }

    /** 一步式：原文 → 切句 → 按命中下标装配 */
    public List<String> assembleFromText(String text, List<Integer> hitIndexes) {
        return assemble(splitSentences(text), hitIndexes);
    }

    public int windowSize() {
        return windowSize;
    }
}
