package cn.chyuan.ai.infrastructure.gateway.retrieval;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import cn.chyuan.ai.domain.rag.service.retrieval.IBM25SearchService;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * BM25检索服务实现 — 使用Lucene实现基于词频的关键词检索
 */
@Slf4j
@ConditionalOnProperty(name = "elasticsearch.enabled", havingValue = "false", matchIfMissing = true)
@ConditionalOnMissingBean(IBM25SearchService.class)
@Service("bm25SearchService")
public class BM25SearchService implements IBM25SearchService {

    private Directory memoryIndex;
    private Analyzer analyzer;
    private IndexWriter indexWriter;
    private SearcherManager searcherManager;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private static final String FIELD_ID = "docId";
    private static final String FIELD_DOCUMENT_ID = "documentId";
    private static final String FIELD_TENANT_ID = "tenantId";
    private static final String FIELD_OWNER_USER_ID = "ownerUserId";
    private static final String FIELD_KNOWLEDGE_BASE_ID = "knowledgeBaseId";
    private static final String FIELD_KNOWLEDGE_BASE_NAME = "knowledgeBaseName";
    private static final String FIELD_SOURCE = "_source";
    private static final String FIELD_CHUNK_INDEX = "chunkIndex";
    private static final String FIELD_CONTENT = "content";

    @PostConstruct
    public void init() {
        try {
            memoryIndex = new ByteBuffersDirectory();
            analyzer = new StandardAnalyzer();
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            indexWriter = new IndexWriter(memoryIndex, config);
            searcherManager = new SearcherManager(indexWriter, false, false, null);
            log.info("BM25内存索引初始化完成");
        } catch (IOException e) {
            log.error("BM25索引初始化失败", e);
            throw new RuntimeException("BM25索引初始化失败", e);
        }
    }

    @PreDestroy
    public void destroy() {
        try {
            if (searcherManager != null) {
                searcherManager.close();
            }
            if (indexWriter != null) {
                indexWriter.close();
            }
            if (memoryIndex != null) {
                memoryIndex.close();
            }
            log.info("BM25索引已关闭");
        } catch (IOException e) {
            log.warn("关闭BM25索引异常", e);
        }
    }

    @Override
    public List<VectorSearchResultVO> search(String query, int topK) {
        return search(query, topK, null);
    }

    @Override
    public List<VectorSearchResultVO> search(String query, int topK, TenantScopeVO scope) {
        log.info("BM25检索: query={}, topK={}", query, topK);

        lock.readLock().lock();
        try {
            // 检查索引是否有文档
            if (indexWriter.getDocStats().numDocs == 0) {
                log.warn("BM25索引为空，跳过检索");
                return Collections.emptyList();
            }

            // 从SearcherManager获取IndexSearcher（复用Reader）
            IndexSearcher searcher = searcherManager.acquire();
            try {
                // 使用BM25Similarity（Lucene默认）
                searcher.setSimilarity(new BM25Similarity());

                // 构建查询
                QueryParser parser = new QueryParser(FIELD_CONTENT, analyzer);
                Query parsedQuery = parser.parse(QueryParser.escape(query));
                Query finalQuery = buildScopedQuery(parsedQuery, scope);

                // 执行检索
                TopDocs topDocs = searcher.search(finalQuery, topK);

                // 构建结果
                List<VectorSearchResultVO> results = new ArrayList<>();
                for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                    Document doc = searcher.doc(scoreDoc.doc);
                    String docId = doc.get(FIELD_ID);
                    String content = doc.get(FIELD_CONTENT);
                    String documentId = doc.get(FIELD_DOCUMENT_ID);
                    String tenantId = doc.get(FIELD_TENANT_ID);
                    String ownerUserId = doc.get(FIELD_OWNER_USER_ID);
                    String knowledgeBaseId = doc.get(FIELD_KNOWLEDGE_BASE_ID);
                    String knowledgeBaseName = doc.get(FIELD_KNOWLEDGE_BASE_NAME);
                    String source = doc.get(FIELD_SOURCE);
                    String chunkIndex = doc.get(FIELD_CHUNK_INDEX);

                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("docId", docId);
                    metadata.put("documentId", documentId);
                    metadata.put("tenantId", tenantId);
                    metadata.put("ownerUserId", ownerUserId);
                    metadata.put("knowledgeBaseId", knowledgeBaseId);
                    metadata.put("knowledgeBaseName", knowledgeBaseName);
                    metadata.put("_source", source);
                    if (chunkIndex != null) {
                        metadata.put("chunkIndex", Integer.parseInt(chunkIndex));
                    }
                    metadata.put("retrievalType", "bm25");

                    results.add(VectorSearchResultVO.builder()
                            .content(content)
                            .score(scoreDoc.score)
                            .metadata(metadata)
                            .build());
                }

                log.info("BM25检索完成: resultCount={}", results.size());
                return results;
            } finally {
                searcherManager.release(searcher);
            }

        } catch (Exception e) {
            log.error("BM25检索失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void addDocument(String docId, String content) {
        addDocument(docId, content, Collections.emptyMap());
    }

    @Override
    public void addDocument(String docId, String content, Map<String, Object> metadata) {
        lock.writeLock().lock();
        try {
            indexWriter.addDocument(buildDocument(docId, content, metadata));
            indexWriter.commit();
            searcherManager.maybeRefresh();
            log.debug("BM25添加文档: docId={}", docId);
        } catch (IOException e) {
            log.error("BM25添加文档失败: docId={}", docId, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void addDocuments(Map<String, String> documents) {
        addDocuments(documents, Collections.emptyMap());
    }

    @Override
    public void addDocuments(Map<String, String> documents, Map<String, Map<String, Object>> metadataByDocId) {
        lock.writeLock().lock();
        try {
            for (Map.Entry<String, String> entry : documents.entrySet()) {
                indexWriter.addDocument(buildDocument(
                        entry.getKey(),
                        entry.getValue(),
                        metadataByDocId.getOrDefault(entry.getKey(), Collections.emptyMap())
                ));
            }
            indexWriter.commit();
            searcherManager.maybeRefresh();
            log.info("BM25批量添加文档: count={}", documents.size());
        } catch (IOException e) {
            log.error("BM25批量添加文档失败", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void removeDocument(String docId) {
        removeDocument(docId, null);
    }

    @Override
    public void removeDocument(String docId, TenantScopeVO scope) {
        lock.writeLock().lock();
        try {
            if (scope == null) {
                indexWriter.deleteDocuments(new Term(FIELD_DOCUMENT_ID, docId));
            } else {
                BooleanQuery.Builder builder = new BooleanQuery.Builder();
                builder.add(new TermQuery(new Term(FIELD_DOCUMENT_ID, docId)), BooleanClause.Occur.MUST);
                builder.add(new TermQuery(new Term(FIELD_TENANT_ID, scope.getTenantId())), BooleanClause.Occur.MUST);
                builder.add(new TermQuery(new Term(FIELD_OWNER_USER_ID, scope.getOwnerUserId())), BooleanClause.Occur.MUST);
                indexWriter.deleteDocuments(builder.build());
            }
            indexWriter.commit();
            searcherManager.maybeRefresh();
            log.debug("BM25删除文档: docId={}", docId);
        } catch (IOException e) {
            log.error("BM25删除文档失败: docId={}", docId, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void clearIndex() {
        lock.writeLock().lock();
        try {
            indexWriter.deleteAll();
            indexWriter.commit();
            log.info("BM25索引已清空");
        } catch (IOException e) {
            log.error("清空BM25索引失败", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public int getDocumentCount() {
        lock.readLock().lock();
        try {
            return (int) indexWriter.getDocStats().numDocs;
        } finally {
            lock.readLock().unlock();
        }
    }

    private Query buildScopedQuery(Query baseQuery, TenantScopeVO scope) {
        if (scope == null) {
            return baseQuery;
        }
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(baseQuery, BooleanClause.Occur.MUST);
        builder.add(new TermQuery(new Term(FIELD_TENANT_ID, scope.getTenantId())), BooleanClause.Occur.MUST);
        builder.add(new TermQuery(new Term(FIELD_OWNER_USER_ID, scope.getOwnerUserId())), BooleanClause.Occur.MUST);
        return builder.build();
    }

    private Document buildDocument(String docId, String content, Map<String, Object> metadata) {
        Document doc = new Document();
        doc.add(new StringField(FIELD_ID, docId, Field.Store.YES));
        doc.add(new TextField(FIELD_CONTENT, content, Field.Store.YES));
        Object documentId = metadata.get("documentId");
        if (documentId != null) {
            doc.add(new StringField(FIELD_DOCUMENT_ID, String.valueOf(documentId), Field.Store.YES));
        }
        Object tenantId = metadata.get("tenantId");
        if (tenantId != null) {
            doc.add(new StringField(FIELD_TENANT_ID, String.valueOf(tenantId), Field.Store.YES));
        }
        Object ownerUserId = metadata.get("ownerUserId");
        if (ownerUserId != null) {
            doc.add(new StringField(FIELD_OWNER_USER_ID, String.valueOf(ownerUserId), Field.Store.YES));
        }
        Object knowledgeBaseId = metadata.get("knowledgeBaseId");
        if (knowledgeBaseId != null) {
            doc.add(new StringField(FIELD_KNOWLEDGE_BASE_ID, String.valueOf(knowledgeBaseId), Field.Store.YES));
        }
        Object knowledgeBaseName = metadata.get("knowledgeBaseName");
        if (knowledgeBaseName != null) {
            doc.add(new StringField(FIELD_KNOWLEDGE_BASE_NAME, String.valueOf(knowledgeBaseName), Field.Store.YES));
        }
        Object source = metadata.get("_source");
        if (source != null) {
            doc.add(new StringField(FIELD_SOURCE, String.valueOf(source), Field.Store.YES));
        }
        Object chunkIndex = metadata.get("chunkIndex");
        if (chunkIndex != null) {
            doc.add(new StringField(FIELD_CHUNK_INDEX, String.valueOf(chunkIndex), Field.Store.YES));
        }
        return doc;
    }

}
