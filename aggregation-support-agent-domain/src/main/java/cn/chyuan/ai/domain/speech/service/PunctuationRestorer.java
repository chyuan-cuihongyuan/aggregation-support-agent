package cn.chyuan.ai.domain.speech.service;

import java.util.List;
import java.util.Set;

/**
 * 标点恢复（工单 0380 AU2，whisper 标点思想规则化）。
 * 转写词序列 + 词后停顿时长 → 规则表插标点：长停顿句号/中停顿逗号/疑问词结尾问号/句长上限强制断句。
 * 规则参数可配；幂等（已带标点词不重复插）。纯函数。
 */
public class PunctuationRestorer {

    /** 词元：文本 + 词后停顿毫秒 */
    public record Word(String text, long gapAfterMs) {
    }

    private final long longPauseMs;
    private final long midPauseMs;
    private final int maxSentenceChars;
    private final Set<String> questionTailWords;

    public PunctuationRestorer(long longPauseMs, long midPauseMs, int maxSentenceChars, Set<String> questionTailWords) {
        if (longPauseMs < midPauseMs) {
            throw new IllegalArgumentException("长停顿阈值不可小于中停顿阈值");
        }
        if (maxSentenceChars < 1) {
            throw new IllegalArgumentException("句长上限至少 1 字符");
        }
        this.longPauseMs = longPauseMs;
        this.midPauseMs = midPauseMs;
        this.maxSentenceChars = maxSentenceChars;
        this.questionTailWords = questionTailWords == null ? Set.of() : questionTailWords;
    }

    /** 默认中文规则 */
    public static PunctuationRestorer defaults() {
        return new PunctuationRestorer(600L, 250L, 30, Set.of("吗", "呢", "么", "什么", "怎么"));
    }

    /**
     * 恢复标点：返回带标点文本（词间插标点，句尾句号/问号）。
     */
    public String restore(List<Word> words) {
        StringBuilder out = new StringBuilder();
        int sentenceChars = 0;
        for (int i = 0; i < words.size(); i++) {
            Word word = words.get(i);
            String text = word.text().trim();
            if (text.isEmpty()) {
                continue;
            }
            // 幂等：已带标点的词原样保留，不再追加
            boolean alreadyPunctuated = text.endsWith("。") || text.endsWith("？")
                    || text.endsWith("！") || text.endsWith("，");
            out.append(text);
            sentenceChars += text.length();
            if (alreadyPunctuated) {
                sentenceChars = 0;
                continue;
            }
            boolean last = i == words.size() - 1;
            boolean question = questionTailWords.stream().anyMatch(text::endsWith);
            long gap = word.gapAfterMs();
            if (question) {
                out.append('？');
                sentenceChars = 0;
            } else if (last || gap >= longPauseMs || sentenceChars >= maxSentenceChars) {
                out.append('。');
                sentenceChars = 0;
            } else if (gap >= midPauseMs) {
                out.append('，');
                sentenceChars = 0;
            }
        }
        return out.toString();
    }
}
