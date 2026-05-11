package cn.chyuan.ai.domain.rag.service.chunker;

import cn.chyuan.ai.domain.rag.model.entity.DocumentChunkEntity;
import cn.chyuan.ai.domain.rag.model.valobj.ParsedDocumentVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 语义分块器 — 基于句子边界和语义完整性进行文档分块
 * <p>
 * 分块策略：
 * <ul>
 *   <li>优先按句子边界分割（句号、问号、感叹号）</li>
 *   <li>保持语义完整性，不在句子中间截断</li>
 *   <li>支持配置最大分块大小和重叠字符数</li>
 *   <li>保留文档结构信息（标题、章节）</li>
 * </ul>
 */
@Slf4j
@Component
public class SemanticChunker {

    /** 每个分块最大字符数 */
    @Value("${document.chunk.max-size:800}")
    private int chunkMaxSize;

    /** 分块之间的重叠字符数 */
    @Value("${document.chunk.overlap:100}")
    private int chunkOverlap;

    /** 中文句子结束符 */
    private static final Pattern CN_SENTENCE_END = Pattern.compile("[。！？；\\n]");

    /** 英文句子结束符 */
    private static final Pattern EN_SENTENCE_END = Pattern.compile("[.!?;\\n]");

    /** 混合句子结束符 */
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？；.!?;\\n]");

    /**
     * 对解析后的文档进行语义分块
     *
     * @param parsedDocument 解析后的文档
     * @param fileName       文件名
     * @return 文档分块列表
     */
    public List<DocumentChunkEntity> chunk(ParsedDocumentVO parsedDocument, String fileName) {
        log.info("开始语义分块: fileName={}, sectionCount={}", fileName,
                parsedDocument.getSections() != null ? parsedDocument.getSections().size() : 0);

        List<DocumentChunkEntity> chunks = new ArrayList<>();

        if (parsedDocument.getSections() != null && !parsedDocument.getSections().isEmpty()) {
            // 按章节分块
            int globalChunkIndex = 0;
            for (ParsedDocumentVO.DocumentSection section : parsedDocument.getSections()) {
                List<DocumentChunkEntity> sectionChunks = chunkSection(section, globalChunkIndex, fileName,
                        parsedDocument.getExtension());
                chunks.addAll(sectionChunks);
                globalChunkIndex += sectionChunks.size();
            }
        } else {
            // 按全文分块
            chunks = chunkText(parsedDocument.getTextContent(), fileName, parsedDocument.getExtension());
        }

        log.info("语义分块完成: fileName={}, chunkCount={}", fileName, chunks.size());
        return chunks;
    }

    /**
     * 对单个章节进行分块
     */
    private List<DocumentChunkEntity> chunkSection(ParsedDocumentVO.DocumentSection section, int startChunkIndex,
                                                    String fileName, String extension) {
        List<DocumentChunkEntity> chunks = new ArrayList<>();
        String content = section.getContent();

        if (content == null || content.trim().isEmpty()) {
            return chunks;
        }

        // 章节内容较短，直接作为一个分块
        if (content.length() <= chunkMaxSize) {
            chunks.add(buildChunkEntity(content, startChunkIndex, 1, fileName, extension, section.getTitle()));
            return chunks;
        }

        // 按句子边界分块
        List<String> sentences = splitBySentences(content);
        List<DocumentChunkEntity> pendingChunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        int chunkIndex = startChunkIndex;

        for (String sentence : sentences) {
            // 如果当前分块加上新句子超过最大长度，保存当前分块
            if (currentChunk.length() > 0 && currentChunk.length() + sentence.length() > chunkMaxSize) {
                pendingChunks.add(buildChunkEntity(currentChunk.toString().trim(), chunkIndex++, 0,
                        fileName, extension, section.getTitle()));

                // 新分块包含重叠部分以保持语义连贯
                String overlap = getOverlapText(currentChunk.toString().trim());
                currentChunk = new StringBuilder(overlap);
            }
            currentChunk.append(sentence);
        }

        // 保存最后一个分块
        if (currentChunk.length() > 0) {
            pendingChunks.add(buildChunkEntity(currentChunk.toString().trim(), chunkIndex, 0,
                    fileName, extension, section.getTitle()));
        }

        // 更新 totalChunks
        int total = pendingChunks.size();
        for (DocumentChunkEntity chunk : pendingChunks) {
            chunk.getMetadata().put("totalChunks", total);
            chunks.add(chunk);
        }

        return chunks;
    }

    /**
     * 对全文进行分块
     */
    private List<DocumentChunkEntity> chunkText(String text, String fileName, String extension) {
        List<DocumentChunkEntity> chunks = new ArrayList<>();

        if (text == null || text.trim().isEmpty()) {
            return chunks;
        }

        // 按句子边界分块
        List<String> sentences = splitBySentences(text);
        List<DocumentChunkEntity> pendingChunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        int chunkIndex = 0;

        for (String sentence : sentences) {
            if (currentChunk.length() > 0 && currentChunk.length() + sentence.length() > chunkMaxSize) {
                pendingChunks.add(buildChunkEntity(currentChunk.toString().trim(), chunkIndex++, 0,
                        fileName, extension, null));

                String overlap = getOverlapText(currentChunk.toString().trim());
                currentChunk = new StringBuilder(overlap);
            }
            currentChunk.append(sentence);
        }

        if (currentChunk.length() > 0) {
            pendingChunks.add(buildChunkEntity(currentChunk.toString().trim(), chunkIndex, 0,
                    fileName, extension, null));
        }

        // 更新 totalChunks
        int total = pendingChunks.size();
        for (DocumentChunkEntity chunk : pendingChunks) {
            chunk.getMetadata().put("totalChunks", total);
            chunks.add(chunk);
        }

        return chunks;
    }

    /**
     * 按句子边界分割文本
     */
    private List<String> splitBySentences(String text) {
        List<String> sentences = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return sentences;
        }

        // 使用正则表达式按句子分割
        String[] parts = SENTENCE_END.split(text, -1);
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < parts.length; i++) {
            current.append(parts[i]);

            // 检查原字符串中该位置后是否有句子结束符
            int pos = getEndPosition(parts, i);
            if (pos < text.length()) {
                char endChar = text.charAt(pos);
                if (isSentenceEnd(endChar)) {
                    current.append(endChar);
                    String sentence = current.toString().trim();
                    if (!sentence.isEmpty()) {
                        sentences.add(sentence);
                    }
                    current = new StringBuilder();
                }
            }
        }

        // 处理最后一部分
        if (current.length() > 0) {
            String sentence = current.toString().trim();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
        }

        return sentences;
    }

    /**
     * 计算分割位置
     */
    private int getEndPosition(String[] parts, int index) {
        int pos = 0;
        for (int i = 0; i <= index; i++) {
            pos += parts[i].length();
            if (i < index) {
                pos++; // 分隔符
            }
        }
        return pos;
    }

    /**
     * 判断是否为句子结束符
     */
    private boolean isSentenceEnd(char c) {
        return c == '。' || c == '！' || c == '？' || c == '；' ||
               c == '.' || c == '!' || c == '?' || c == ';' || c == '\n';
    }

    /**
     * 获取重叠文本 — 从文本末尾提取指定长度，尽量在句子边界截断
     */
    private String getOverlapText(String text) {
        int overlapSize = Math.min(chunkOverlap, text.length());
        if (overlapSize <= 0) {
            return "";
        }

        String overlap = text.substring(text.length() - overlapSize);

        // 尝试在句子边界截断
        int lastSentenceEnd = Math.max(
                overlap.lastIndexOf('。'),
                Math.max(overlap.lastIndexOf('！'),
                        Math.max(overlap.lastIndexOf('？'),
                                Math.max(overlap.lastIndexOf('.'),
                                        Math.max(overlap.lastIndexOf('!'),
                                                overlap.lastIndexOf('?'))))));

        if (lastSentenceEnd > overlapSize / 2) {
            return overlap.substring(lastSentenceEnd + 1).trim();
        }

        return overlap.trim();
    }

    /**
     * 构建文档分块实体
     */
    private DocumentChunkEntity buildChunkEntity(String content, int chunkIndex, int totalChunks,
                                                  String fileName, String extension, String title) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("_source", fileName);
        metadata.put("_file_name", fileName);
        metadata.put("_extension", extension);
        metadata.put("chunkIndex", chunkIndex);
        metadata.put("totalChunks", totalChunks);
        if (title != null) {
            metadata.put("title", title);
        }

        return DocumentChunkEntity.builder()
                .id(fileName + "_chunk_" + chunkIndex)
                .content(content)
                .metadata(metadata)
                .build();
    }

}
