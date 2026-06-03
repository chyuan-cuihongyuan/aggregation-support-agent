package cn.chyuan.ai.domain.rag.service.retrieval;

import cn.chyuan.ai.domain.rag.model.entity.DocumentChunkEntity;
import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import cn.chyuan.ai.domain.rag.service.chunker.SentenceUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 句子窗口检索服务 — 精细检索 + 扩展返回窗口
 * <p>
 * 核心思想：
 * <ul>
 *   <li>存储时把文档切成单个句子，每个句子单独向量化</li>
 *   <li>检索命中一个句子后，并不只返回这一个句子</li>
 *   <li>而是把这个句子前后各N个句子一起返回，形成上下文窗口</li>
 * </ul>
 * <p>
 * 优势：
 * <ul>
 *   <li>检索粒度细（单句），定位准确，相关性高</li>
 *   <li>给LLM的是完整上下文窗口，信息完整</li>
 * </ul>
 */
@Slf4j
@Service
public class SentenceWindowRetriever {

    /** 上下文窗口大小（前后各N句） */
    @Value("${rag.retrieval.sentence-window.size}")
    private int windowSize;

    /** 句子结束符 */
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？；.!?;\\n]");

    /**
     * 对检索结果扩展上下文窗口
     *
     * @param results 原始检索结果（单句级别）
     * @param documentContent 原始文档内容（用于提取上下文）
     * @return 扩展后的结果
     */
    public List<VectorSearchResultVO> expandWindows(
            List<VectorSearchResultVO> results,
            String documentContent) {

        log.info("句子窗口扩展: resultCount={}, windowSize={}", results.size(), windowSize);

        // 将文档按句子分割
        List<String> sentences = splitBySentences(documentContent);
        log.debug("文档句子数: {}", sentences.size());

        // 对每个结果扩展上下文窗口
        List<VectorSearchResultVO> expandedResults = new ArrayList<>();
        for (VectorSearchResultVO result : results) {
            VectorSearchResultVO expanded = expandWindow(result, sentences);
            expandedResults.add(expanded);
        }

        log.info("句子窗口扩展完成: expandedCount={}", expandedResults.size());
        return expandedResults;
    }

    /**
     * 对单个结果扩展上下文窗口
     */
    private VectorSearchResultVO expandWindow(VectorSearchResultVO result, List<String> sentences) {
        String hitSentence = result.getContent();

        // 找到命中句子在文档中的位置
        int hitIndex = findSentenceIndex(sentences, hitSentence);

        if (hitIndex < 0) {
            // 未找到，返回原结果
            return result;
        }

        // 计算窗口范围
        int startIndex = Math.max(0, hitIndex - windowSize);
        int endIndex = Math.min(sentences.size() - 1, hitIndex + windowSize);

        // 提取窗口内容
        StringBuilder windowContent = new StringBuilder();
        for (int i = startIndex; i <= endIndex; i++) {
            if (i > startIndex) {
                windowContent.append("\n");
            }
            windowContent.append(sentences.get(i));
        }

        // 构建扩展后的结果
        Map<String, Object> metadata = new HashMap<>(result.getMetadata());
        metadata.put("windowStart", startIndex);
        metadata.put("windowEnd", endIndex);
        metadata.put("hitIndex", hitIndex);
        metadata.put("originalContent", hitSentence);

        return VectorSearchResultVO.builder()
                .content(windowContent.toString())
                .score(result.getScore())
                .metadata(metadata)
                .build();
    }

    /**
     * 找到句子在文档中的位置
     */
    private int findSentenceIndex(List<String> sentences, String targetSentence) {
        // 精确匹配
        for (int i = 0; i < sentences.size(); i++) {
            if (sentences.get(i).equals(targetSentence)) {
                return i;
            }
        }

        // 包含匹配
        String normalizedTarget = targetSentence.trim();
        for (int i = 0; i < sentences.size(); i++) {
            if (sentences.get(i).contains(normalizedTarget) || normalizedTarget.contains(sentences.get(i))) {
                return i;
            }
        }

        return -1;
    }

    /**
     * 按句子边界分割文本
     */
    private List<String> splitBySentences(String text) {
        return SentenceUtils.splitBySentences(text);
    }

    private int getEndPosition(String[] parts, int index) {
        return SentenceUtils.getEndPosition(parts, index);
    }

    private boolean isSentenceEnd(char c) {
        return SentenceUtils.isSentenceEnd(c);
    }

}
