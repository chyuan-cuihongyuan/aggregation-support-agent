package cn.chyuan.ai.domain.rag.service;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import cn.chyuan.ai.domain.rag.adapter.port.IDocumentParserFactory;
import cn.chyuan.ai.domain.rag.adapter.repository.IDocumentMetadataRepository;
import cn.chyuan.ai.domain.rag.adapter.repository.IRagTraceRepository;
import cn.chyuan.ai.domain.rag.adapter.repository.IVectorStoreRepository;
import cn.chyuan.ai.domain.rag.model.entity.DocumentChunkEntity;
import cn.chyuan.ai.domain.rag.model.entity.DocumentMetadataEntity;
import cn.chyuan.ai.domain.rag.model.entity.RagTraceEntity;
import cn.chyuan.ai.domain.rag.model.valobj.DocumentUploadCommand;
import cn.chyuan.ai.domain.rag.model.valobj.ParsedDocumentVO;
import cn.chyuan.ai.domain.rag.model.valobj.RagSourceVO;
import cn.chyuan.ai.domain.rag.model.valobj.SearchOutcomeVO;
import cn.chyuan.ai.domain.rag.model.valobj.SearchResultDetailVO;
import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import cn.chyuan.ai.domain.rag.service.chunker.SemanticChunker;
import cn.chyuan.ai.domain.rag.service.retrieval.IBM25SearchService;
import cn.chyuan.ai.domain.rag.service.retrieval.IHybridSearchService;
import cn.chyuan.ai.domain.rag.support.RagSourceCollector;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import com.google.common.hash.Hashing;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.nio.charset.StandardCharsets;
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
    @Value("${rag.top-k}")
    private int defaultTopK;

    /** 分块最大字符数 */
    @Value("${document.chunk.max-size}")
    private int chunkMaxSize;

    /** 分块重叠字符数 */
    @Value("${document.chunk.overlap}")
    private int chunkOverlap;

    @Resource
    private IEmbeddingService embeddingService;

    @Resource
    private IVectorStoreRepository vectorStoreRepository;

    @Resource
    private IDocumentParserFactory documentParserFactory;

    @Resource
    private SemanticChunker semanticChunker;

    @Resource
    private IDocumentMetadataRepository documentMetadataRepository;

    @Resource(name = "hybridSearchService")
    private IHybridSearchService hybridSearchService;

    @Resource(name = "bm25SearchService")
    private IBM25SearchService bm25SearchService;

    @Resource
    private IRagTraceRepository ragTraceRepository;

    @Override
    public void uploadDocument(DocumentUploadCommand command) {
        String documentId = command.getDocumentId() != null && !command.getDocumentId().isBlank()
                ? command.getDocumentId()
                : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String contentHash = computeContentHash(command);
        rejectDuplicateContent(command, contentHash);
        String fileName = command.getFileName();
        log.info("开始处理文档上传: fileName={}", fileName);

        String extension = "";
        if (fileName != null && fileName.contains(".")) {
            extension = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
        }

        long fileSize = command.getRawContent() != null ? command.getRawContent().length
                : (command.getContent() != null ? command.getContent().length() : 0L);

        DocumentMetadataEntity metadata = DocumentMetadataEntity.builder()
                .documentId(documentId)
                .tenantId(command.getTenantId() != null ? command.getTenantId() : command.getUserId())
                .ownerUserId(command.getUserId() != null ? command.getUserId() : "")
                .knowledgeBaseId(command.getKnowledgeBaseId() != null ? command.getKnowledgeBaseId() : "")
                .knowledgeBaseName(command.getKnowledgeBaseName() != null ? command.getKnowledgeBaseName() : "")
                .visibility("private")
                .deletedFlag(0)
                .fileName(fileName)
                .fileExtension(extension)
                .fileSize(fileSize)
                .mimeType(command.getMimeType())
                .contentHash(contentHash)
                .processingStatus("processing")
                .userId(command.getUserId() != null ? command.getUserId() : "")
                .build();
        documentMetadataRepository.save(metadata);

        try {
            byte[] rawBytes;
            if (command.getRawContent() != null) {
                rawBytes = command.getRawContent();
            } else if (command.getContent() != null) {
                rawBytes = command.getContent().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            } else {
                throw new RuntimeException("文档内容为空");
            }

            ParsedDocumentVO parsedDocument = documentParserFactory.parse(rawBytes, fileName, command.getMimeType());

            List<DocumentChunkEntity> chunks = semanticChunker.chunk(parsedDocument, fileName);

            if (chunks.isEmpty()) {
                log.warn("文档分块结果为空，跳过处理: {}", fileName);
                documentMetadataRepository.updateStatus(documentId, "success", 0, 0, 0, "文档分块结果为空");
                return;
            }

            for (DocumentChunkEntity chunk : chunks) {
                enrichChunkMetadata(chunk, documentId, metadata);
            }

            List<String> texts = chunks.stream().map(DocumentChunkEntity::getContent).collect(Collectors.toList());
            List<float[]> vectors = embeddingService.embedBatch(texts);

            for (int i = 0; i < chunks.size(); i++) {
                chunks.get(i).setVector(vectors.get(i));
            }

            vectorStoreRepository.insertChunks(chunks);

            // 写入 BM25 索引（Elasticsearch），确保三库（MySQL + Milvus + ES）数据一致
            if (bm25SearchService != null) {
                try {
                    Map<String, String> documents = new HashMap<>();
                    Map<String, Map<String, Object>> metadataByDocId = new HashMap<>();
                    for (DocumentChunkEntity chunk : chunks) {
                        documents.put(chunk.getId(), chunk.getContent());
                        metadataByDocId.put(chunk.getId(), chunk.getMetadata());
                    }
                    bm25SearchService.addDocuments(documents, metadataByDocId);
                    log.info("添加到BM25索引: documentId={}, count={}", documentId, documents.size());
                } catch (Exception e) {
                    log.warn("添加到BM25索引失败(非致命): documentId={}, err={}", documentId, e.getMessage());
                }
            }

            int totalChars = parsedDocument.getTextContent() != null ? parsedDocument.getTextContent().length() : 0;
            int sectionCount = parsedDocument.getSections() != null ? parsedDocument.getSections().size() : 0;
            documentMetadataRepository.updateStatus(documentId, "success", chunks.size(), totalChars, sectionCount, "");

            log.info("文档上传处理完成: fileName={}, documentId={}, chunkCount={}", fileName, documentId, chunks.size());
        } catch (Exception e) {
            log.error("文档上传处理失败: fileName={}, documentId={}", fileName, documentId, e);
            String errMsg = e.getMessage() != null
                    ? e.getMessage().substring(0, Math.min(e.getMessage().length(), 500)) : "未知错误";
            documentMetadataRepository.updateStatus(documentId, "failed", 0, 0, 0, errMsg);
            throw e;
        }
    }

    @Override
    public void deleteDocument(String documentId, TenantScopeVO scope) {
        vectorStoreRepository.deleteByDocumentId(documentId, scope);
        if (bm25SearchService != null) {
            bm25SearchService.removeDocument(documentId, scope);
        }
        documentMetadataRepository.markDeletedByDocumentId(documentId, scope);
    }

    @Override
    public List<VectorSearchResultVO> search(String query, int topK) {
        return search(query, topK, null);
    }

    @Override
    public List<VectorSearchResultVO> search(String query, int topK, TenantScopeVO scope) {
        log.info("语义检索: query={}, topK={}", query, topK);

        // 1. 将查询文本嵌入为向量
        float[] queryVector = embeddingService.embed(query);

        // 2. 在 Milvus 中执行 L2 距离相似性检索
        List<VectorSearchResultVO> results = vectorStoreRepository.search(queryVector, topK, scope);

        log.info("语义检索完成: resultCount={}", results.size());
        return results;
    }

    @Override
    public SearchOutcomeVO searchWithTrace(String query, int topK, TenantScopeVO scope) {
        // 强校验租户作用域，避免越权检索
        if (scope == null) {
            throw new IllegalArgumentException("租户作用域(scope)不能为空");
        }
        // topK 非法时回退默认值
        int effectiveTopK = topK <= 0 ? defaultTopK : topK;

        String traceId = UUID.randomUUID().toString().replace("-", "");

        // 复用既有的向量检索逻辑，本期基础版不做 Query 改写
        List<VectorSearchResultVO> rawResults = this.search(query, effectiveTopK, scope);

        // 将检索结果转换为可展示的证据片段
        List<RagSourceVO> sources = rawResults == null ? Collections.emptyList()
                : rawResults.stream().map(this::convertToRagSource).collect(Collectors.toList());

        // 异步落库 RAG 追踪（@Async("ragTraceExecutor")），Repository 内部全量 try/catch，异常不会传播到此
        RagTraceEntity trace = RagTraceEntity.builder()
                .traceId(traceId)
                .tenantId(scope.getTenantId())
                .ownerUserId(scope.getOwnerUserId())
                .sessionId("")
                .agentId("")
                .queryText(query)
                .rewriteText(null)
                .retrievalTopk(effectiveTopK)
                .sources(sources)
                .build();
        ragTraceRepository.save(trace);

        // 写入收集器，便于 ChatService 出口取出 traceId 拼到响应
        RagSourceCollector.setTraceId(traceId);
        // 记录检索元数据，供请求出口上报 RAG 检索日志到可观测性服务
        RagSourceCollector.setRetrievalMeta(query, null, effectiveTopK);

        return SearchOutcomeVO.builder()
                .traceId(traceId)
                .originalQuery(query)
                .rewriteQuery(null)
                .topK(effectiveTopK)
                .sources(sources)
                .rawResults(rawResults)
                .build();
    }

    /**
     * 将向量检索结果转换为证据片段；snippet 截断 200 字符以控制 JSON 体积
     */
    private RagSourceVO convertToRagSource(VectorSearchResultVO result) {
        Map<String, Object> metadata = result.getMetadata();
        String documentId = null;
        String documentName = null;
        String chunkId = null;
        Integer chunkIndex = null;
        if (metadata != null) {
            Object documentIdObj = metadata.get("documentId");
            if (documentIdObj != null) {
                documentId = documentIdObj.toString();
            }
            Object sourceObj = metadata.get("_source");
            if (sourceObj == null) {
                sourceObj = metadata.get("_file_name");
            }
            if (sourceObj != null) {
                documentName = sourceObj.toString();
            }
            Object chunkIdObj = metadata.get("chunkId");
            if (chunkIdObj == null) {
                chunkIdObj = metadata.get("id");
            }
            if (chunkIdObj != null) {
                chunkId = chunkIdObj.toString();
            }
            Object chunkIndexObj = metadata.get("chunkIndex");
            if (chunkIndexObj instanceof Number) {
                chunkIndex = ((Number) chunkIndexObj).intValue();
            }
        }

        String content = result.getContent();
        String snippet = null;
        if (content != null) {
            snippet = content.length() > 200 ? content.substring(0, 200) : content;
        }

        return RagSourceVO.builder()
                .documentId(documentId)
                .documentName(documentName)
                .chunkId(chunkId)
                .chunkIndex(chunkIndex)
                .score(result.getScore())
                .retrievalType("vector")
                .snippet(snippet)
                .build();
    }

    @Override
    public boolean healthCheck() {
        return vectorStoreRepository.healthCheck();
    }

    @Override
    public SearchResultDetailVO searchWithDetails(String query, int topK) {
        return searchWithDetails(query, topK, null);
    }

    @Override
    public SearchResultDetailVO searchWithDetails(String query, int topK, TenantScopeVO scope) {
        log.info("检索测试: query={}, topK={}", query, topK);

        List<VectorSearchResultVO> vectorResults = Collections.emptyList();
        List<VectorSearchResultVO> bm25Results = Collections.emptyList();
        List<VectorSearchResultVO> hybridResults = Collections.emptyList();

        try {
            if (hybridSearchService != null && hybridSearchService.isAvailable()) {
                vectorResults = hybridSearchService.vectorSearch(query, topK, scope);
            } else if (vectorStoreRepository != null) {
                List<float[]> queryVectors = embeddingService.embedBatch(Collections.singletonList(query));
                if (!queryVectors.isEmpty()) {
                    vectorResults = vectorStoreRepository.search(queryVectors.get(0), topK, scope);
                }
            }
        } catch (Exception e) {
            log.error("向量检索失败: {}", e.getMessage());
        }

        try {
            if (bm25SearchService != null) {
                bm25Results = bm25SearchService.search(query, topK, scope);
            }
        } catch (Exception e) {
            log.error("BM25检索失败: {}", e.getMessage());
        }

        try {
            if (hybridSearchService != null && hybridSearchService.isAvailable()) {
                hybridResults = hybridSearchService.search(query, topK, scope);
            }
        } catch (Exception e) {
            log.error("混合检索失败: {}", e.getMessage());
        }

        return SearchResultDetailVO.builder()
                .query(query)
                .vectorResults(convertToItems(vectorResults))
                .bm25Results(convertToItems(bm25Results))
                .hybridResults(convertToItems(hybridResults))
                .build();
    }

    private List<SearchResultDetailVO.SearchItem> convertToItems(List<VectorSearchResultVO> results) {
        if (results == null) return Collections.emptyList();
        return results.stream().map(r -> {
            String source = r.getMetadata() != null ? (String) r.getMetadata().get("_source") : null;
            Integer chunkIndex = r.getMetadata() != null && r.getMetadata().get("chunkIndex") != null
                    ? ((Number) r.getMetadata().get("chunkIndex")).intValue() : null;
            String knowledgeBaseId = r.getMetadata() != null && r.getMetadata().get("knowledgeBaseId") != null
                    ? String.valueOf(r.getMetadata().get("knowledgeBaseId")) : null;
            String knowledgeBaseName = r.getMetadata() != null && r.getMetadata().get("knowledgeBaseName") != null
                    ? String.valueOf(r.getMetadata().get("knowledgeBaseName")) : null;
            return SearchResultDetailVO.SearchItem.builder()
                    .content(r.getContent())
                    .score(r.getScore())
                    .source(source)
                    .chunkIndex(chunkIndex)
                    .knowledgeBaseId(knowledgeBaseId)
                    .knowledgeBaseName(knowledgeBaseName)
                    .build();
        }).collect(Collectors.toList());
    }

    // ========== 文档分块逻辑（从 Aggregation-Support-Agent-java 迁移并适配） ==========

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

    private void enrichChunkMetadata(DocumentChunkEntity chunk, String documentId, DocumentMetadataEntity metadataEntity) {
        Map<String, Object> metadata = chunk.getMetadata();
        metadata.put("documentId", documentId);
        metadata.put("tenantId", metadataEntity.getTenantId());
        metadata.put("ownerUserId", metadataEntity.getOwnerUserId());
        metadata.put("knowledgeBaseId", metadataEntity.getKnowledgeBaseId());
        metadata.put("knowledgeBaseName", metadataEntity.getKnowledgeBaseName());
        metadata.put("visibility", metadataEntity.getVisibility());
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

    /**
     * 计算上传内容指纹 — 优先原始字节（二进制格式），缺失时退回文本内容，空内容亦产生稳定指纹
     */
    private String computeContentHash(DocumentUploadCommand command) {
        byte[] bytes = command.getRawContent() != null
                ? command.getRawContent()
                : command.getContent() != null
                ? command.getContent().getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        return Hashing.sha256().hashBytes(bytes).toString();
    }

    /**
     * 同租户+用户内容去重 — 命中同哈希未删除文档时拒绝上传；作用域无效时跳过判重（防误拒）
     */
    private void rejectDuplicateContent(DocumentUploadCommand command, String contentHash) {
        TenantScopeVO scope = TenantScopeVO.builder()
                .tenantId(command.getTenantId() != null ? command.getTenantId() : command.getUserId())
                .ownerUserId(command.getUserId() != null ? command.getUserId() : "")
                .build();
        if (!scope.isValid()) {
            return;
        }
        if (documentMetadataRepository.existsByContentHash(contentHash, scope)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "相同内容的文档已存在（文件名: " + command.getFileName() + "），请勿重复上传");
        }
    }

}
