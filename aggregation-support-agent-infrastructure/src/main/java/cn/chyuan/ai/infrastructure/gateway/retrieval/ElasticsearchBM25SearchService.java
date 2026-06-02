package cn.chyuan.ai.infrastructure.gateway.retrieval;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import cn.chyuan.ai.domain.rag.service.retrieval.IBM25SearchService;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.*;

/**
 * Elasticsearch BM25检索服务实现 — 生产环境推荐
 * <p>
 * 优势：
 * <ul>
 *   <li>支持中文分词（IK分词器）</li>
 *   <li>支持持久化存储</li>
 *   <li>支持分布式部署</li>
 *   <li>支持复杂查询语法</li>
 * </ul>
 */
@Slf4j
@Primary
@Service("bm25SearchService")
@ConditionalOnClass(ElasticsearchClient.class)
@ConditionalOnBean(ElasticsearchClient.class)
@ConditionalOnProperty(name = "elasticsearch.enabled", havingValue = "true")
public class ElasticsearchBM25SearchService implements IBM25SearchService {

    @Autowired
    private ElasticsearchClient esClient;

    @Value("${elasticsearch.index.name:rag-documents}")
    private String indexName;

    @PostConstruct
    public void init() {
        try {
            createIndexIfNotExists();
            log.info("Elasticsearch BM25索引初始化完成: index={}", indexName);
        } catch (Exception e) {
            log.error("Elasticsearch索引初始化失败", e);
        }
    }

    /**
     * 创建索引（如果不存在）
     */
    private void createIndexIfNotExists() throws IOException {
        boolean exists = esClient.indices().exists(i -> i.index(indexName)).value();

        if (!exists) {
            esClient.indices().create(i -> i
                    .index(indexName)
                    .settings(s -> s
                            .numberOfShards("1")
                            .numberOfReplicas("0")
                            .analysis(a -> a
                                    .analyzer("ik_smart", an -> an
                                            .custom(c -> c
                                                    .tokenizer("ik_smart")
                                                    .filter("lowercase")
                                            )
                                    )
                            )
                    )
                    .mappings(m -> m
                            .properties("content", p -> p
                                    .text(t -> t
                                            .analyzer("ik_smart")
                                            .searchAnalyzer("ik_smart")
                                    )
                            )
                            .properties("docId", p -> p
                                    .keyword(k -> k)
                            )
                            .properties("documentId", p -> p
                                    .keyword(k -> k)
                            )
                            .properties("tenantId", p -> p
                                    .keyword(k -> k)
                            )
                            .properties("ownerUserId", p -> p
                                    .keyword(k -> k)
                            )
                            .properties("knowledgeBaseId", p -> p
                                    .keyword(k -> k)
                            )
                            .properties("knowledgeBaseName", p -> p
                                    .keyword(k -> k)
                            )
                    )
            );
            log.info("创建Elasticsearch索引: {}", indexName);
        }
    }

    @Override
    public List<VectorSearchResultVO> search(String query, int topK) {
        return search(query, topK, null);
    }

    @Override
    public List<VectorSearchResultVO> search(String query, int topK, TenantScopeVO scope) {
        log.info("Elasticsearch BM25检索: query={}, topK={}", query, topK);

        try {
            SearchResponse<Map> response = esClient.search(s -> s
                            .index(indexName)
                            .query(q -> q.bool(b -> {
                                b.must(m -> m.multiMatch(mm -> mm
                                        .fields("content")
                                        .query(query)
                                        .type(co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType.BestFields)
                                        .fuzziness("AUTO")));
                                if (scope != null) {
                                    b.filter(f -> f.term(t -> t.field("tenantId").value(scope.getTenantId())));
                                    b.filter(f -> f.term(t -> t.field("ownerUserId").value(scope.getOwnerUserId())));
                                }
                                return b;
                            }))
                            .size(topK),
                    Map.class
            );

            List<VectorSearchResultVO> results = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                Map<String, Object> source = hit.source();
                if (source != null) {
                    String content = (String) source.get("content");
                    String docId = (String) source.get("docId");
                    String documentId = (String) source.get("documentId");
                    String tenantId = (String) source.get("tenantId");
                    String ownerUserId = (String) source.get("ownerUserId");
                    String knowledgeBaseId = (String) source.get("knowledgeBaseId");
                    String knowledgeBaseName = (String) source.get("knowledgeBaseName");
                    Object sourceName = source.get("_source");
                    Object fileName = source.get("_file_name");
                    Object chunkIndex = source.get("chunkIndex");

                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("docId", docId);
                    metadata.put("documentId", documentId);
                    metadata.put("tenantId", tenantId);
                    metadata.put("ownerUserId", ownerUserId);
                    metadata.put("knowledgeBaseId", knowledgeBaseId);
                    metadata.put("knowledgeBaseName", knowledgeBaseName);
                    if (sourceName != null) {
                        metadata.put("_source", sourceName);
                    }
                    if (fileName != null) {
                        metadata.put("_file_name", fileName);
                    }
                    if (chunkIndex != null) {
                        metadata.put("chunkIndex", chunkIndex);
                    }
                    metadata.put("retrievalType", "bm25");
                    metadata.put("esScore", hit.score());

                    results.add(VectorSearchResultVO.builder()
                            .content(content)
                            .score(hit.score() != null ? hit.score().floatValue() : 0f)
                            .metadata(metadata)
                            .build());
                }
            }

            log.info("Elasticsearch BM25检索完成: resultCount={}", results.size());
            return results;

        } catch (IOException e) {
            log.error("Elasticsearch检索失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public void addDocument(String docId, String content) {
        addDocument(docId, content, Collections.emptyMap());
    }

    @Override
    public void addDocument(String docId, String content, Map<String, Object> metadata) {
        try {
            Map<String, Object> doc = new HashMap<>();
            doc.put("docId", docId);
            doc.put("content", content);
            doc.putAll(metadata);

            esClient.index(i -> i
                    .index(indexName)
                    .id(docId)
                    .document(doc)
            );

            log.debug("Elasticsearch添加文档: docId={}", docId);
        } catch (IOException e) {
            log.error("Elasticsearch添加文档失败: docId={}", docId, e);
        }
    }

    @Override
    public void addDocuments(Map<String, String> documents) {
        addDocuments(documents, Collections.emptyMap());
    }

    @Override
    public void addDocuments(Map<String, String> documents, Map<String, Map<String, Object>> metadataByDocId) {
        try {
            var bulkRequest = new co.elastic.clients.elasticsearch.core.BulkRequest.Builder();

            for (Map.Entry<String, String> entry : documents.entrySet()) {
                Map<String, Object> doc = new HashMap<>();
                doc.put("docId", entry.getKey());
                doc.put("content", entry.getValue());
                doc.putAll(metadataByDocId.getOrDefault(entry.getKey(), Collections.emptyMap()));

                bulkRequest.operations(op -> op
                        .index(idx -> idx
                                .index(indexName)
                                .id(entry.getKey())
                                .document(doc)
                        )
                );
            }

            var response = esClient.bulk(bulkRequest.build());
            if (response.errors()) {
                response.items().stream()
                        .filter(item -> item.error() != null)
                        .findFirst()
                        .ifPresent(item -> log.error("Elasticsearch批量添加文档部分失败: id={}, error={}",
                                item.id(), item.error().reason()));
                throw new IOException("Elasticsearch批量添加文档存在失败项");
            }
            log.info("Elasticsearch批量添加文档: index={}, count={}", indexName, documents.size());
        } catch (IOException e) {
            log.error("Elasticsearch批量添加文档失败", e);
            throw new RuntimeException("Elasticsearch批量添加文档失败", e);
        }
    }

    @Override
    public void removeDocument(String docId) {
        removeDocument(docId, null);
    }

    @Override
    public void removeDocument(String docId, TenantScopeVO scope) {
        try {
            esClient.deleteByQuery(d -> d
                    .index(indexName)
                    .query(q -> q.bool(b -> {
                        b.must(m -> m.term(t -> t.field("documentId").value(docId)));
                        if (scope != null) {
                            b.filter(f -> f.term(t -> t.field("tenantId").value(scope.getTenantId())));
                            b.filter(f -> f.term(t -> t.field("ownerUserId").value(scope.getOwnerUserId())));
                        }
                        return b;
                    }))
            );
            log.debug("Elasticsearch删除文档: docId={}", docId);
        } catch (IOException e) {
            log.error("Elasticsearch删除文档失败: docId={}", docId, e);
        }
    }

    @Override
    public void clearIndex() {
        try {
            esClient.deleteByQuery(d -> d
                    .index(indexName)
                    .query(q -> q.matchAll(m -> m))
            );
            log.info("Elasticsearch索引已清空: index={}", indexName);
        } catch (IOException e) {
            log.error("清空Elasticsearch索引失败", e);
        }
    }

    @Override
    public int getDocumentCount() {
        try {
            var response = esClient.count(c -> c
                    .index(indexName)
                    .query(q -> q.matchAll(m -> m))
            );
            return (int) response.count();
        } catch (IOException e) {
            log.error("获取Elasticsearch文档数量失败", e);
            return 0;
        }
    }

}
