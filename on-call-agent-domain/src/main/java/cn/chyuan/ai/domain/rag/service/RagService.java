package cn.chyuan.ai.domain.rag.service;

import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import cn.chyuan.ai.domain.rag.adapter.port.IDocumentParserFactory;
import cn.chyuan.ai.domain.rag.adapter.repository.IVectorStoreRepository;
import cn.chyuan.ai.domain.rag.model.entity.DocumentChunkEntity;
import cn.chyuan.ai.domain.rag.model.valobj.DocumentUploadCommand;
import cn.chyuan.ai.domain.rag.model.valobj.ParsedDocumentVO;
import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import cn.chyuan.ai.domain.rag.service.chunker.SemanticChunker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * RAG 服务实现 — 文档解析 → 语义分块 → 嵌入 → Milvus 存储 / 查询嵌入 → L2 检索
 * <p>
 * 文档预处理流程：
 * <ul>
 *   <li>支持多种文档格式（TXT、Markdown、PDF、Word、HTML）</li>
 *   <li>使用语义分块器，基于句子边界分割，保持语义完整性</li>
 *   <li>保留文档结构信息（标题、章节、元数据）</li>
 * </ul>
 */
@Slf4j
@Service
@ConditionalOnMissingBean(EnhancedRagService.class)
@ConditionalOnProperty(name = "milvus.enabled", havingValue = "true", matchIfMissing = false)
public class RagService implements IRagService {

    /** 检索返回的最相似文档数量 */
    @Value("${rag.top-k:3}")
    private int defaultTopK;

    /** 分块最大字符数 */
    @Value("${document.chunk.max-size:1000}")
    private int chunkMaxSize;

    /** 分块重叠字符数 */
    @Value("${document.chunk.overlap:100}")
    private int chunkOverlap;

    @Resource
    private IEmbeddingService embeddingService;

    @Resource
    private IVectorStoreRepository vectorStoreRepository;

    @Resource
    private IDocumentParserFactory documentParserFactory;

    @Resource
    private SemanticChunker semanticChunker;

    @Override
    public void uploadDocument(DocumentUploadCommand command) {
        log.info("开始处理文档上传: fileName={}, contentLength={}", command.getFileName(), command.getContent().length());

        // 1. 解析文档：根据文件类型自动选择解析器
        ParsedDocumentVO parsedDocument = documentParserFactory.parse(
                command.getContent().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                command.getFileName(),
                command.getMimeType()
        );

        // 2. 语义分块：基于句子边界进行智能分块
        List<DocumentChunkEntity> chunks = semanticChunker.chunk(parsedDocument, command.getFileName());

        if (chunks.isEmpty()) {
            log.warn("文档分块结果为空，跳过处理: {}", command.getFileName());
            return;
        }

        // 3. 批量嵌入：将所有分块文本转换为向量
        List<String> texts = chunks.stream().map(DocumentChunkEntity::getContent).collect(java.util.stream.Collectors.toList());
        List<float[]> vectors = embeddingService.embedBatch(texts);

        // 4. 将向量写回分块实体
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setVector(vectors.get(i));
        }

        // 5. 写入向量数据库
        vectorStoreRepository.insertChunks(chunks);

        log.info("文档上传处理完成: fileName={}, chunkCount={}", command.getFileName(), chunks.size());
    }

    @Override
    public List<VectorSearchResultVO> search(String query, int topK) {
        log.info("语义检索: query={}, topK={}", query, topK);

        // 1. 将查询文本嵌入为向量
        float[] queryVector = embeddingService.embed(query);

        // 2. 在 Milvus 中执行 L2 距离相似性检索
        List<VectorSearchResultVO> results = vectorStoreRepository.search(queryVector, topK);

        log.info("语义检索完成: resultCount={}", results.size());
        return results;
    }

    @Override
    public boolean healthCheck() {
        return vectorStoreRepository.healthCheck();
    }

    // ========== 文档分块逻辑（从 OnCall-Agent-java 迁移并适配） ==========

    /**
     * 智能分块文档 — 优先按 Markdown 标题分割，再按段落边界细分
     *
     * @param content  文档内容
     * @param fileName 文件名（用于元数据和日志）
     * @return 文档分块实体列表
     */
    private List<DocumentChunkEntity> chunkDocument(String content, String fileName) {
        List<DocumentChunkEntity> chunks = new ArrayList<>();

        if (content == null || content.trim().isEmpty()) {
            log.warn("文档内容为空: {}", fileName);
            return chunks;
        }

        String extension = fileName.lastIndexOf('.') >= 0 ? fileName.substring(fileName.lastIndexOf('.')) : ".txt";

        // 1. 按 Markdown 标题分割章节
        List<Section> sections = splitByHeadings(content);

        // 2. 对每个章节进行进一步分块
        int globalChunkIndex = 0;
        for (Section section : sections) {
            List<DocumentChunkEntity> sectionChunks = chunkSection(section, globalChunkIndex, fileName, extension);
            chunks.addAll(sectionChunks);
            globalChunkIndex += sectionChunks.size();
        }

        log.info("文档分块完成: {} -> {} 个分块", fileName, chunks.size());
        return chunks;
    }

    /**
     * 按 Markdown 标题（# ~ ######）分割文档为章节
     */
    private List<Section> splitByHeadings(String content) {
        List<Section> sections = new ArrayList<>();
        Pattern headingPattern = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
        Matcher matcher = headingPattern.matcher(content);

        int lastEnd = 0;
        String currentTitle = null;

        while (matcher.find()) {
            if (lastEnd < matcher.start()) {
                String sectionContent = content.substring(lastEnd, matcher.start()).trim();
                if (!sectionContent.isEmpty()) {
                    sections.add(new Section(currentTitle, sectionContent, lastEnd));
                }
            }
            currentTitle = matcher.group(2).trim();
            lastEnd = matcher.start();
        }

        // 添加最后一个章节
        if (lastEnd < content.length()) {
            String sectionContent = content.substring(lastEnd).trim();
            if (!sectionContent.isEmpty()) {
                sections.add(new Section(currentTitle, sectionContent, lastEnd));
            }
        }

        // 没有找到标题时，整个文档作为一个章节
        if (sections.isEmpty()) {
            sections.add(new Section(null, content, 0));
        }

        return sections;
    }

    /**
     * 对单个章节进行分块 — 如果章节不超过最大尺寸则整体保留，否则按段落边界分割
     */
    private List<DocumentChunkEntity> chunkSection(Section section, int startChunkIndex, String fileName, String extension) {
        List<DocumentChunkEntity> chunks = new ArrayList<>();
        String sectionContent = section.content;

        // 章节内容较短，直接作为一个分块
        if (sectionContent.length() <= chunkMaxSize) {
            chunks.add(buildChunkEntity(sectionContent, startChunkIndex, 1, fileName, extension, section.title));
            return chunks;
        }

        // 章节内容较长，按段落边界分割
        List<String> paragraphs = splitByParagraphs(sectionContent);
        StringBuilder currentChunk = new StringBuilder();
        int chunkIndex = startChunkIndex;
        List<DocumentChunkEntity> pendingChunks = new ArrayList<>();

        for (String paragraph : paragraphs) {
            if (currentChunk.length() > 0 && currentChunk.length() + paragraph.length() > chunkMaxSize) {
                // 保存当前分块
                pendingChunks.add(buildChunkEntity(currentChunk.toString().trim(), chunkIndex++, 0, fileName, extension, section.title));
                // 新分块包含重叠部分以保持语义连贯
                String overlap = getOverlapText(currentChunk.toString().trim());
                currentChunk = new StringBuilder(overlap);
            }
            currentChunk.append(paragraph).append("\n\n");
        }

        // 保存最后一个分块
        if (currentChunk.length() > 0) {
            pendingChunks.add(buildChunkEntity(currentChunk.toString().trim(), chunkIndex, 0, fileName, extension, section.title));
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
     * 按双换行符分割段落
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
     * 获取重叠文本 — 从文本末尾提取指定长度，尽量在句子边界截断
     */
    private String getOverlapText(String text) {
        int overlapSize = Math.min(chunkOverlap, text.length());
        if (overlapSize <= 0) {
            return "";
        }
        String overlap = text.substring(text.length() - overlapSize);
        // 尝试在句子边界截断（句号、问号、感叹号）
        int lastSentenceEnd = Math.max(overlap.lastIndexOf('。'), Math.max(overlap.lastIndexOf('？'), overlap.lastIndexOf('！')));
        if (lastSentenceEnd > overlapSize / 2) {
            return overlap.substring(lastSentenceEnd + 1).trim();
        }
        return overlap.trim();
    }

    /**
     * 构建文档分块实体
     */
    private DocumentChunkEntity buildChunkEntity(String content, int chunkIndex, int totalChunks, String fileName, String extension, String title) {
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

    /** 章节内部类 */
    private static class Section {
        String title;
        String content;
        int startIndex;

        Section(String title, String content, int startIndex) {
            this.title = title;
            this.content = content;
            this.startIndex = startIndex;
        }
    }

}
