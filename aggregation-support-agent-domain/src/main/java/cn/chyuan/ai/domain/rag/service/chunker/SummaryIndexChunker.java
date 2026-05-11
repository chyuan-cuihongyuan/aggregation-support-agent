package cn.chyuan.ai.domain.rag.service.chunker;

import cn.chyuan.ai.domain.rag.model.entity.DocumentChunkEntity;
import cn.chyuan.ai.domain.rag.model.valobj.ParsedDocumentVO;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 摘要索引分块器 — 为每个chunk生成摘要，用摘要建向量索引
 * <p>
 * 核心思想：
 * <ul>
 *   <li>文档原文表述可能很散，摘要是对核心意思的提炼</li>
 *   <li>摘要语义更聚焦，在向量空间里和用户问题更接近</li>
 *   <li>检索时用摘要匹配，命中后给LLM原始段落阅读</li>
 * </ul>
 * <p>
 * 流程：
 * <ol>
 *   <li>文档按段落/章节分块</li>
 *   <li>LLM为每个chunk生成摘要</li>
 *   <li>摘要和原始chunk都存储，摘要建向量索引</li>
 *   <li>检索时匹配摘要向量，返回原始chunk给LLM</li>
 * </ol>
 */
@Slf4j
@Component
public class SummaryIndexChunker {

    @Autowired(required = false)
    private ChatModel chatModel;

    /** 摘要最大长度 */
    @Value("${document.chunk.summary.max-length:200}")
    private int summaryMaxLength;

    /** 原始chunk最大大小 */
    @Value("${document.chunk.max-size:800}")
    private int chunkMaxSize;

    /**
     * 创建摘要索引
     *
     * @param parsedDocument 解析后的文档
     * @param fileName       文件名
     * @return 摘要索引结果（包含摘要chunk和原始chunk）
     */
    public SummaryIndexResult createSummaryIndex(ParsedDocumentVO parsedDocument, String fileName) {
        log.info("开始创建摘要索引: fileName={}", fileName);

        // 1. 先按常规方式分块
        List<DocumentChunkEntity> originalChunks = createOriginalChunks(parsedDocument, fileName);

        // 2. 为每个chunk生成摘要
        List<DocumentChunkEntity> summaryChunks = new ArrayList<>();
        for (DocumentChunkEntity originalChunk : originalChunks) {
            String summary = generateSummary(originalChunk.getContent());
            DocumentChunkEntity summaryChunk = createSummaryChunk(summary, originalChunk, fileName);
            summaryChunks.add(summaryChunk);
        }

        log.info("摘要索引创建完成: fileName={}, originalCount={}, summaryCount={}",
                fileName, originalChunks.size(), summaryChunks.size());

        return new SummaryIndexResult(originalChunks, summaryChunks);
    }

    /**
     * 创建原始chunks
     */
    private List<DocumentChunkEntity> createOriginalChunks(ParsedDocumentVO parsedDocument, String fileName) {
        List<DocumentChunkEntity> chunks = new ArrayList<>();

        if (parsedDocument.getSections() != null && !parsedDocument.getSections().isEmpty()) {
            int chunkIndex = 0;
            for (ParsedDocumentVO.DocumentSection section : parsedDocument.getSections()) {
                String content = section.getContent();
                if (content == null || content.trim().isEmpty()) continue;

                // 章节较短，直接作为一个chunk
                if (content.length() <= chunkMaxSize) {
                    chunks.add(buildOriginalChunk(content, chunkIndex++, fileName,
                            parsedDocument.getExtension(), section.getTitle()));
                } else {
                    // 章节较长，按段落分割
                    List<String> paragraphs = splitByParagraphs(content);
                    StringBuilder currentChunk = new StringBuilder();

                    for (String paragraph : paragraphs) {
                        if (currentChunk.length() > 0 && currentChunk.length() + paragraph.length() > chunkMaxSize) {
                            chunks.add(buildOriginalChunk(currentChunk.toString().trim(), chunkIndex++,
                                    fileName, parsedDocument.getExtension(), section.getTitle()));
                            currentChunk = new StringBuilder();
                        }
                        currentChunk.append(paragraph).append("\n\n");
                    }

                    if (currentChunk.length() > 0) {
                        chunks.add(buildOriginalChunk(currentChunk.toString().trim(), chunkIndex,
                                fileName, parsedDocument.getExtension(), section.getTitle()));
                    }
                }
            }
        } else {
            // 没有章节结构，按段落分割
            String content = parsedDocument.getTextContent();
            List<String> paragraphs = splitByParagraphs(content);
            int chunkIndex = 0;
            StringBuilder currentChunk = new StringBuilder();

            for (String paragraph : paragraphs) {
                if (currentChunk.length() > 0 && currentChunk.length() + paragraph.length() > chunkMaxSize) {
                    chunks.add(buildOriginalChunk(currentChunk.toString().trim(), chunkIndex++,
                            fileName, parsedDocument.getExtension(), null));
                    currentChunk = new StringBuilder();
                }
                currentChunk.append(paragraph).append("\n\n");
            }

            if (currentChunk.length() > 0) {
                chunks.add(buildOriginalChunk(currentChunk.toString().trim(), chunkIndex,
                        fileName, parsedDocument.getExtension(), null));
            }
        }

        return chunks;
    }

    /**
     * 使用LLM生成摘要
     */
    private String generateSummary(String content) {
        if (chatModel == null) {
            log.warn("ChatModel未配置，使用截断原文作为摘要");
            return content.length() > summaryMaxLength
                    ? content.substring(0, summaryMaxLength) + "..."
                    : content;
        }

        String prompt = """
                请为以下文本生成一段简洁的摘要，要求：
                1. 提炼核心要点，去除冗余信息
                2. 保留关键术语和概念
                3. 长度控制在100-200字
                4. 使用陈述性语言
                5. 只返回摘要内容，不要添加"摘要："等前缀
                
                原文：
                %s
                
                摘要：
                """.formatted(truncateContent(content, 2000)); // 限制输入长度

        try {
            String summary = chatModel.call(new Prompt(new UserMessage(prompt)))
                    .getResult().getOutput().getText();
            return summary.trim();
        } catch (Exception e) {
            log.warn("摘要生成失败，使用截断原文: {}", e.getMessage());
            // 降级：使用原文前N个字符作为摘要
            return content.length() > summaryMaxLength
                    ? content.substring(0, summaryMaxLength) + "..."
                    : content;
        }
    }

    /**
     * 创建摘要chunk
     */
    private DocumentChunkEntity createSummaryChunk(String summary, DocumentChunkEntity originalChunk, String fileName) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("_source", fileName);
        metadata.put("_file_name", fileName);
        metadata.put("chunkType", "summary");
        metadata.put("originalChunkId", originalChunk.getId());
        metadata.put("originalContent", originalChunk.getContent()); // 保存原始内容用于检索后返回

        return DocumentChunkEntity.builder()
                .id(originalChunk.getId() + "_summary")
                .content(summary)
                .metadata(metadata)
                .build();
    }

    /**
     * 构建原始chunk
     */
    private DocumentChunkEntity buildOriginalChunk(String content, int chunkIndex,
                                                    String fileName, String extension, String title) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("_source", fileName);
        metadata.put("_file_name", fileName);
        metadata.put("_extension", extension);
        metadata.put("chunkIndex", chunkIndex);
        metadata.put("chunkType", "original");
        if (title != null) {
            metadata.put("title", title);
        }

        return DocumentChunkEntity.builder()
                .id(fileName + "_original_" + chunkIndex)
                .content(content)
                .metadata(metadata)
                .build();
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
     * 截断内容
     */
    private String truncateContent(String content, int maxLength) {
        if (content == null) return "";
        return content.length() > maxLength ? content.substring(0, maxLength) + "..." : content;
    }

    /**
     * 摘要索引结果
     */
    @Data
    public static class SummaryIndexResult {
        /** 原始chunks（用于LLM阅读） */
        private final List<DocumentChunkEntity> originalChunks;
        /** 摘要chunks（用于向量索引） */
        private final List<DocumentChunkEntity> summaryChunks;

        public SummaryIndexResult(List<DocumentChunkEntity> originalChunks, List<DocumentChunkEntity> summaryChunks) {
            this.originalChunks = originalChunks;
            this.summaryChunks = summaryChunks;
        }
    }

}
