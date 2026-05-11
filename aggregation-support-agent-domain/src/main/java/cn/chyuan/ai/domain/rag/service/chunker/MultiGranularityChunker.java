package cn.chyuan.ai.domain.rag.service.chunker;

import cn.chyuan.ai.domain.rag.model.entity.DocumentChunkEntity;
import cn.chyuan.ai.domain.rag.model.valobj.ParsedDocumentVO;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 多粒度分层索引分块器 — 同时建章节级、段落级、句子级三层索引
 * <p>
 * 核心思想：
 * <ul>
 *   <li>不同类型的问题适合不同粒度</li>
 *   <li>概念性问题（如"什么是RAG"）→ 章节级索引</li>
 *   <li>细节性问题（如"退款需要几个工作日"）→ 句子级索引</li>
 *   <li>系统根据问题类型自动选择合适的粒度去检索</li>
 * </ul>
 * <p>
 * 三层索引结构：
 * <ul>
 *   <li>L1 章节级（Section）：2000-5000字符，包含完整上下文</li>
 *   <li>L2 段落级（Paragraph）：500-1000字符，平衡粒度和上下文</li>
 *   <li>L3 句子级（Sentence）：100-300字符，最细粒度，精准匹配</li>
 * </ul>
 */
@Slf4j
@Component
public class MultiGranularityChunker {

    /** 章节级chunk最大大小 */
    @Value("${document.chunk.section-size:3000}")
    private int sectionSize;

    /** 段落级chunk最大大小 */
    @Value("${document.chunk.paragraph-size:800}")
    private int paragraphSize;

    /** 句子级chunk最大大小 */
    @Value("${document.chunk.sentence-size:200}")
    private int sentenceSize;

    /** 句子结束符 */
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？；.!?;\\n]");

    /**
     * 创建多粒度索引
     *
     * @param parsedDocument 解析后的文档
     * @param fileName       文件名
     * @return 多粒度索引结果
     */
    public MultiGranularityResult createMultiGranularityIndex(ParsedDocumentVO parsedDocument, String fileName) {
        log.info("开始创建多粒度索引: fileName={}", fileName);

        List<DocumentChunkEntity> sectionChunks = new ArrayList<>();
        List<DocumentChunkEntity> paragraphChunks = new ArrayList<>();
        List<DocumentChunkEntity> sentenceChunks = new ArrayList<>();

        if (parsedDocument.getSections() != null && !parsedDocument.getSections().isEmpty()) {
            int sectionIndex = 0;
            int paragraphIndex = 0;
            int sentenceIndex = 0;

            for (ParsedDocumentVO.DocumentSection section : parsedDocument.getSections()) {
                String content = section.getContent();
                if (content == null || content.trim().isEmpty()) continue;

                // L1: 章节级 - 直接使用整个章节或按章节大小分割
                List<DocumentChunkEntity> sections = createSectionChunks(
                        content, sectionIndex, fileName, parsedDocument.getExtension(), section.getTitle());
                sectionChunks.addAll(sections);
                sectionIndex += sections.size();

                // L2: 段落级 - 按段落分割
                List<DocumentChunkEntity> paragraphs = createParagraphChunks(
                        content, paragraphIndex, fileName, parsedDocument.getExtension(), section.getTitle());
                paragraphChunks.addAll(paragraphs);
                paragraphIndex += paragraphs.size();

                // L3: 句子级 - 按句子边界分割
                List<DocumentChunkEntity> sentences = createSentenceChunks(
                        content, sentenceIndex, fileName, parsedDocument.getExtension(), section.getTitle());
                sentenceChunks.addAll(sentences);
                sentenceIndex += sentences.size();
            }
        } else {
            // 没有章节结构，按全文处理
            String content = parsedDocument.getTextContent();

            sectionChunks = createSectionChunks(content, 0, fileName, parsedDocument.getExtension(), null);
            paragraphChunks = createParagraphChunks(content, 0, fileName, parsedDocument.getExtension(), null);
            sentenceChunks = createSentenceChunks(content, 0, fileName, parsedDocument.getExtension(), null);
        }

        // 更新totalChunks
        updateTotalChunks(sectionChunks);
        updateTotalChunks(paragraphChunks);
        updateTotalChunks(sentenceChunks);

        log.info("多粒度索引创建完成: fileName={}, sectionCount={}, paragraphCount={}, sentenceCount={}",
                fileName, sectionChunks.size(), paragraphChunks.size(), sentenceChunks.size());

        return new MultiGranularityResult(sectionChunks, paragraphChunks, sentenceChunks);
    }

    /**
     * 创建章节级chunks（L1）
     */
    private List<DocumentChunkEntity> createSectionChunks(String content, int startIndex,
                                                           String fileName, String extension, String title) {
        List<DocumentChunkEntity> chunks = new ArrayList<>();

        if (content == null || content.trim().isEmpty()) {
            return chunks;
        }

        // 内容较短，直接作为一个章节chunk
        if (content.length() <= sectionSize) {
            chunks.add(buildChunkEntity(content, startIndex, fileName, extension, title, "section"));
            return chunks;
        }

        // 按段落分割，合并成章节级chunk
        List<String> paragraphs = splitByParagraphs(content);
        StringBuilder currentChunk = new StringBuilder();
        int chunkIndex = startIndex;

        for (String paragraph : paragraphs) {
            if (currentChunk.length() > 0 && currentChunk.length() + paragraph.length() > sectionSize) {
                chunks.add(buildChunkEntity(currentChunk.toString().trim(), chunkIndex++,
                        fileName, extension, title, "section"));
                currentChunk = new StringBuilder();
            }
            currentChunk.append(paragraph).append("\n\n");
        }

        if (currentChunk.length() > 0) {
            chunks.add(buildChunkEntity(currentChunk.toString().trim(), chunkIndex,
                    fileName, extension, title, "section"));
        }

        return chunks;
    }

    /**
     * 创建段落级chunks（L2）
     */
    private List<DocumentChunkEntity> createParagraphChunks(String content, int startIndex,
                                                             String fileName, String extension, String title) {
        List<DocumentChunkEntity> chunks = new ArrayList<>();

        if (content == null || content.trim().isEmpty()) {
            return chunks;
        }

        List<String> paragraphs = splitByParagraphs(content);
        int chunkIndex = startIndex;

        for (String paragraph : paragraphs) {
            if (paragraph.trim().isEmpty()) continue;

            // 段落较短，直接作为一个chunk
            if (paragraph.length() <= paragraphSize) {
                chunks.add(buildChunkEntity(paragraph.trim(), chunkIndex++,
                        fileName, extension, title, "paragraph"));
            } else {
                // 段落较长，按句子分割后合并
                List<String> sentences = splitBySentences(paragraph);
                StringBuilder currentChunk = new StringBuilder();

                for (String sentence : sentences) {
                    if (currentChunk.length() > 0 && currentChunk.length() + sentence.length() > paragraphSize) {
                        chunks.add(buildChunkEntity(currentChunk.toString().trim(), chunkIndex++,
                                fileName, extension, title, "paragraph"));
                        currentChunk = new StringBuilder();
                    }
                    currentChunk.append(sentence);
                }

                if (currentChunk.length() > 0) {
                    chunks.add(buildChunkEntity(currentChunk.toString().trim(), chunkIndex++,
                            fileName, extension, title, "paragraph"));
                }
            }
        }

        return chunks;
    }

    /**
     * 创建句子级chunks（L3）
     */
    private List<DocumentChunkEntity> createSentenceChunks(String content, int startIndex,
                                                            String fileName, String extension, String title) {
        List<DocumentChunkEntity> chunks = new ArrayList<>();

        if (content == null || content.trim().isEmpty()) {
            return chunks;
        }

        List<String> sentences = splitBySentences(content);
        int chunkIndex = startIndex;
        StringBuilder currentChunk = new StringBuilder();

        for (String sentence : sentences) {
            if (sentence.trim().isEmpty()) continue;

            // 句子较短，合并多个句子
            if (currentChunk.length() > 0 && currentChunk.length() + sentence.length() > sentenceSize) {
                chunks.add(buildChunkEntity(currentChunk.toString().trim(), chunkIndex++,
                        fileName, extension, title, "sentence"));
                currentChunk = new StringBuilder();
            }
            currentChunk.append(sentence);
        }

        if (currentChunk.length() > 0) {
            chunks.add(buildChunkEntity(currentChunk.toString().trim(), chunkIndex,
                    fileName, extension, title, "sentence"));
        }

        return chunks;
    }

    /**
     * 按段落分割
     */
    private List<String> splitByParagraphs(String content) {
        List<String> paragraphs = new ArrayList<>();
        for (String part : content.split("\n\n+")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                paragraphs.add(trimmed);
            }
        }
        return paragraphs;
    }

    /**
     * 按句子分割
     */
    private List<String> splitBySentences(String text) {
        List<String> sentences = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return sentences;
        }

        String[] parts = SENTENCE_END.split(text, -1);
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < parts.length; i++) {
            current.append(parts[i]);

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

        if (current.length() > 0) {
            String sentence = current.toString().trim();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
        }

        return sentences;
    }

    private int getEndPosition(String[] parts, int index) {
        int pos = 0;
        for (int i = 0; i <= index; i++) {
            pos += parts[i].length();
            if (i < index) pos++;
        }
        return pos;
    }

    private boolean isSentenceEnd(char c) {
        return c == '。' || c == '！' || c == '？' || c == '；' ||
               c == '.' || c == '!' || c == '?' || c == ';' || c == '\n';
    }

    /**
     * 构建chunk实体
     */
    private DocumentChunkEntity buildChunkEntity(String content, int chunkIndex,
                                                  String fileName, String extension,
                                                  String title, String granularity) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("_source", fileName);
        metadata.put("_file_name", fileName);
        metadata.put("_extension", extension);
        metadata.put("chunkIndex", chunkIndex);
        metadata.put("granularity", granularity);
        if (title != null) {
            metadata.put("title", title);
        }

        return DocumentChunkEntity.builder()
                .id(fileName + "_" + granularity + "_" + chunkIndex)
                .content(content)
                .metadata(metadata)
                .build();
    }

    /**
     * 更新totalChunks
     */
    private void updateTotalChunks(List<DocumentChunkEntity> chunks) {
        int total = chunks.size();
        for (DocumentChunkEntity chunk : chunks) {
            chunk.getMetadata().put("totalChunks", total);
        }
    }

    /**
     * 多粒度索引结果
     */
    @Data
    public static class MultiGranularityResult {
        /** L1: 章节级chunks（概念性问题） */
        private final List<DocumentChunkEntity> sectionChunks;
        /** L2: 段落级chunks（一般性问题） */
        private final List<DocumentChunkEntity> paragraphChunks;
        /** L3: 句子级chunks（细节性问题） */
        private final List<DocumentChunkEntity> sentenceChunks;

        public MultiGranularityResult(List<DocumentChunkEntity> sectionChunks,
                                       List<DocumentChunkEntity> paragraphChunks,
                                       List<DocumentChunkEntity> sentenceChunks) {
            this.sectionChunks = sectionChunks;
            this.paragraphChunks = paragraphChunks;
            this.sentenceChunks = sentenceChunks;
        }

        /**
         * 获取所有chunks
         */
        public List<DocumentChunkEntity> getAllChunks() {
            List<DocumentChunkEntity> allChunks = new ArrayList<>();
            allChunks.addAll(sectionChunks);
            allChunks.addAll(paragraphChunks);
            allChunks.addAll(sentenceChunks);
            return allChunks;
        }

        /**
         * 根据查询类型选择合适的粒度
         */
        public List<DocumentChunkEntity> getChunksByQueryType(String queryType) {
            return switch (queryType.toLowerCase()) {
                case "concept", "概览", "概述", "什么是", "定义" -> sectionChunks;
                case "detail", "具体", "如何", "怎么", "步骤" -> sentenceChunks;
                default -> paragraphChunks; // 默认使用段落级
            };
        }
    }

}
