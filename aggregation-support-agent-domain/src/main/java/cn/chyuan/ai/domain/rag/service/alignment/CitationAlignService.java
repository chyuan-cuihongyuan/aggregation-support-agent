package cn.chyuan.ai.domain.rag.service.alignment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 引用溯源对齐服务（工单 0169，W7）— 答案句子与命中片段的词面 Jaccard 对齐
 * <p>
 * 纯函数内核（无状态即算）：
 * <ul>
 *   <li>分句：按中英文句读（。！？；!?;、换行）切分答案</li>
 *   <li>分词口径：复用观测服务 TraceQualityCalculator 的 tokenize 口径
 *       （小写、非字母数字中文分隔、中文按单字、英文按整词），保证与评测评分同源</li>
 *   <li>对齐：每句与全部命中片段逐一计算 Jaccard 相似度，取最大者为
 *       最佳来源片段（并列时取索引最小者，保证确定性）</li>
 *   <li>输出：每句最佳来源索引与对齐度、未对齐句列表（对齐度低于阈值）、
 *       对齐均值 = citationScore（供评测复用）</li>
 * </ul>
 */
@Slf4j
@Service
public class CitationAlignService {

    /** 中英文句读切分（含换行；句子保留原文标点，空句丢弃） */
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。！？；!?;\\n])\\s*");

    /** 分词口径与 TraceQualityCalculator 一致：保留小写字母/数字/中文，其余为分隔 */
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9\\u4e00-\\u9fa5]+");
    private static final Pattern CHINESE = Pattern.compile("[\\u4e00-\\u9fa5]");

    /** 默认未对齐阈值：对齐度低于该值的句子视为未对齐 */
    public static final double DEFAULT_UNALIGNED_THRESHOLD = 0.1;

    /** 未对齐阈值（可配） */
    @Value("${rag.citation.align-threshold:0.1}")
    private double unalignedThreshold;

    /** 单句对齐结果 */
    public record SentenceAlignment(String sentence, int bestSourceIndex, double alignment) {
    }

    /** 对齐结果（citationScore = 全部句子对齐度均值，供评测复用） */
    public record CitationAlignResult(List<SentenceAlignment> sentences,
                                      List<String> unalignedSentences,
                                      double citationScore) {
    }

    /**
     * 对齐：答案分句与命中片段词面 Jaccard
     *
     * @param answer 答案全文（可为空：空答案返回空结果、citationScore=0）
     * @param sources 命中片段列表（索引即 bestSourceIndex 取值）
     * @return 对齐结果
     */
    public CitationAlignResult align(String answer, List<String> sources) {
        List<SentenceAlignment> sentences = new ArrayList<>();
        List<String> unaligned = new ArrayList<>();
        List<String> parts = splitSentences(answer);
        if (parts.isEmpty()) {
            // 空答案：无可对齐句，citationScore=0
            return new CitationAlignResult(sentences, unaligned, 0.0d);
        }

        double threshold = effectiveThreshold();
        double sum = 0.0d;
        for (String sentence : parts) {
            int bestIndex = -1;
            double bestScore = 0.0d;
            if (sources != null) {
                Set<String> sentenceTokens = tokenize(sentence);
                for (int i = 0; i < sources.size(); i++) {
                    double score = jaccard(sentenceTokens, tokenize(sources.get(i)));
                    // 严格大于：并列时保留索引最小者（确定性）
                    if (score > bestScore) {
                        bestScore = score;
                        bestIndex = i;
                    }
                }
            }
            sentences.add(new SentenceAlignment(sentence, bestIndex, bestScore));
            sum += bestScore;
            if (bestScore < threshold) {
                unaligned.add(sentence);
            }
        }

        double citationScore = parts.isEmpty() ? 0.0d : sum / parts.size();
        log.debug("引用对齐完成: sentences={}, unaligned={}, citationScore={}",
                sentences.size(), unaligned.size(), citationScore);
        return new CitationAlignResult(sentences, unaligned, citationScore);
    }

    /**
     * 分句：中英文句读切分，保留原文标点，丢弃空白句
     */
    public static List<String> splitSentences(String answer) {
        List<String> sentences = new ArrayList<>();
        if (answer == null || answer.isBlank()) {
            return sentences;
        }
        for (String part : SENTENCE_SPLIT.split(answer.trim())) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                sentences.add(trimmed);
            }
        }
        return sentences;
    }

    /**
     * 分词（与观测服务 TraceQualityCalculator 口径一致）：
     * 小写化，按非字母数字中文分隔，中文按单字、英文按整词
     */
    public static Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        String normalized = text.toLowerCase();
        for (String word : TOKEN_SPLIT.split(normalized)) {
            if (word.isEmpty()) {
                continue;
            }
            if (CHINESE.matcher(word).find()) {
                for (int i = 0; i < word.length(); i++) {
                    char c = word.charAt(i);
                    if (c >= '一' && c <= '龥') {
                        tokens.add(String.valueOf(c));
                    }
                }
            } else {
                tokens.add(word);
            }
        }
        return tokens;
    }

    /**
     * Jaccard 相似度：|A∩B| / |A∪B|；任一空集返回 0
     */
    public static double jaccard(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0.0d;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    private double effectiveThreshold() {
        return unalignedThreshold > 0 ? unalignedThreshold : DEFAULT_UNALIGNED_THRESHOLD;
    }
}
