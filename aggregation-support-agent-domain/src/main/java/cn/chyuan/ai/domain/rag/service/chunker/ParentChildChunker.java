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
 * Parent-Child分块器 — 实现Small-to-Big策略
 * <p>
 * 核心思想：小块检索、大块使用
 * <ul>
 *   <li>子chunk(Child)：细粒度，用于向量检索，语义聚焦</li>
 *   <li>父chunk(Parent)：粗粒度，用于LLM阅读，上下文完整</li>
 *   <li>每个子chunk通过parentId关联到对应的父chunk</li>
 * </ul>
 */
@Slf4j
@Component
public class ParentChildChunker {

    /** 子chunk大小（token估算：1个中文字符≈2 token） */
    @Value("${document.chunk.child-size}")
    private int childSize;

    /** 父chunk大小 */
    @Value("${document.chunk.parent-size}")
    private int parentSize;

    /** 子chunk重叠字符数 */
    @Value("${document.chunk.child-overlap}")
    private int childOverlap;

    /** 句子结束符 */
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？；.!?;\\n]");

    /**
     * 对文档进行Parent-Child分块
     *
     * @param parsedDocument 解析后的文档
     * @param fileName       文件名
     * @return 子chunk列表（用于向量索引）
     */
    public ParentChildChunks chunk(ParsedDocumentVO parsedDocument, String fileName) {
        log.info("开始Parent-Child分块: fileName={}, parentSize={}, childSize={}",
                fileName, parentSize, childSize);

        List<DocumentChunkEntity> parentChunks = new ArrayList<>();
        List<DocumentChunkEntity> childChunks = new ArrayList<>();

        if (parsedDocument.getSections() != null && !parsedDocument.getSections().isEmpty()) {
            // 按章节处理
            int parentIndex = 0;
            for (ParsedDocumentVO.DocumentSection section : parsedDocument.getSections()) {
                // 为每个章节创建父chunk
                List<DocumentChunkEntity> sectionParents = createParentChunks(
                        section.getContent(), parentIndex, fileName,
                        parsedDocument.getExtension(), section.getTitle());

                // 为每个父chunk创建子chunk
                for (DocumentChunkEntity parent : sectionParents) {
                    List<DocumentChunkEntity> children = createChildChunks(
                            parent.getContent(), parent.getId(), childChunks.size(),
                            fileName, parsedDocument.getExtension(), section.getTitle());
                    childChunks.addAll(children);
                }

                parentChunks.addAll(sectionParents);
                parentIndex += sectionParents.size();
            }
        } else {
            // 按全文处理
            parentChunks = createParentChunks(parsedDocument.getTextContent(), 0,
                    fileName, parsedDocument.getExtension(), null);

            for (DocumentChunkEntity parent : parentChunks) {
                List<DocumentChunkEntity> children = createChildChunks(
                        parent.getContent(), parent.getId(), childChunks.size(),
                        fileName, parsedDocument.getExtension(), null);
                childChunks.addAll(children);
            }
        }

        // 更新totalChunks
        updateTotalChunks(parentChunks);
        updateTotalChunks(childChunks);

        log.info("Parent-Child分块完成: fileName={}, parentCount={}, childCount={}",
                fileName, parentChunks.size(), childChunks.size());

        return new ParentChildChunks(parentChunks, childChunks);
    }

    /**
     * 创建父chunk列表
     */
    private List<DocumentChunkEntity> createParentChunks(String content, int startIndex,
                                                          String fileName, String extension, String title) {
        List<DocumentChunkEntity> chunks = new ArrayList<>();

        if (content == null || content.trim().isEmpty()) {
            return chunks;
        }

        // 内容较短，直接作为一个父chunk
        if (content.length() <= parentSize * 2) { // 估算token转换
            chunks.add(buildChunkEntity(content, startIndex, 0, fileName, extension, title, null, "parent"));
            return chunks;
        }

        // 按段落分割
        List<String> paragraphs = splitByParagraphs(content);
        StringBuilder currentChunk = new StringBuilder();
        int chunkIndex = startIndex;

        for (String paragraph : paragraphs) {
            if (currentChunk.length() > 0 && currentChunk.length() + paragraph.length() > parentSize * 2) {
                // 保存当前父chunk
                chunks.add(buildChunkEntity(currentChunk.toString().trim(), chunkIndex++, 0,
                        fileName, extension, title, null, "parent"));
                currentChunk = new StringBuilder();
            }
            currentChunk.append(paragraph).append("\n\n");
        }

        // 保存最后一个父chunk
        if (currentChunk.length() > 0) {
            chunks.add(buildChunkEntity(currentChunk.toString().trim(), chunkIndex, 0,
                    fileName, extension, title, null, "parent"));
        }

        return chunks;
    }

    /**
     * 创建子chunk列表
     */
    private List<DocumentChunkEntity> createChildChunks(String content, String parentId,
                                                         int startIndex, String fileName,
                                                         String extension, String title) {
        List<DocumentChunkEntity> chunks = new ArrayList<>();

        if (content == null || content.trim().isEmpty()) {
            return chunks;
        }

        // 内容较短，直接作为一个子chunk
        if (content.length() <= childSize * 2) {
            chunks.add(buildChunkEntity(content, startIndex, 0, fileName, extension, title, parentId, "child"));
            return chunks;
        }

        // 按句子边界分割
        List<String> sentences = splitBySentences(content);
        StringBuilder currentChunk = new StringBuilder();
        int chunkIndex = startIndex;

        for (String sentence : sentences) {
            if (currentChunk.length() > 0 && currentChunk.length() + sentence.length() > childSize * 2) {
                // 保存当前子chunk
                chunks.add(buildChunkEntity(currentChunk.toString().trim(), chunkIndex++, 0,
                        fileName, extension, title, parentId, "child"));

                // 新chunk包含重叠
                String overlap = getOverlapText(currentChunk.toString().trim());
                currentChunk = new StringBuilder(overlap);
            }
            currentChunk.append(sentence);
        }

        // 保存最后一个子chunk
        if (currentChunk.length() > 0) {
            chunks.add(buildChunkEntity(currentChunk.toString().trim(), chunkIndex, 0,
                    fileName, extension, title, parentId, "child"));
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
     * 按句子边界分割
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

    /**
     * 获取重叠文本
     */
    private String getOverlapText(String text) {
        int overlapSize = Math.min(childOverlap, text.length());
        if (overlapSize <= 0) {
            return "";
        }
        return text.substring(text.length() - overlapSize).trim();
    }

    /**
     * 构建chunk实体
     */
    private DocumentChunkEntity buildChunkEntity(String content, int chunkIndex, int totalChunks,
                                                  String fileName, String extension, String title,
                                                  String parentId, String chunkType) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("_source", fileName);
        metadata.put("_file_name", fileName);
        metadata.put("_extension", extension);
        metadata.put("chunkIndex", chunkIndex);
        metadata.put("totalChunks", totalChunks);
        metadata.put("chunkType", chunkType);
        if (title != null) {
            metadata.put("title", title);
        }
        if (parentId != null) {
            metadata.put("parentId", parentId);
        }

        return DocumentChunkEntity.builder()
                .id(fileName + "_" + chunkType + "_" + chunkIndex)
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
     * Parent-Child分块结果
     */
    @Data
    public static class ParentChildChunks {
        /** 父chunk列表（用于LLM阅读） */
        private final List<DocumentChunkEntity> parentChunks;
        /** 子chunk列表（用于向量索引） */
        private final List<DocumentChunkEntity> childChunks;

        public ParentChildChunks(List<DocumentChunkEntity> parentChunks, List<DocumentChunkEntity> childChunks) {
            this.parentChunks = parentChunks;
            this.childChunks = childChunks;
        }
    }

}
