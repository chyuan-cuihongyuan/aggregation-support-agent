package cn.chyuan.ai.infrastructure.gateway.retrieval;

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
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
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
@Service
@ConditionalOnBean(ElasticsearchClient.class)
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
                    )
            );
            log.info("创建Elasticsearch索引: {}", indexName);
        }
    }

    @Override
    public List<VectorSearchResultVO> search(String query, int topK) {
        log.info("Elasticsearch BM25检索: query={}, topK={}", query, topK);

        try {
            SearchResponse<Map> response = esClient.search(s -> s
                            .index(indexName)
                            .query(q -> q
                                    .multiMatch(m -> m
                                            .fields("content")
                                            .query(query)
                                            .type(co.elastic.clients.elasticsearch._types.QueryType.BestFields)
                                            .fuzziness("AUTO")
                                    )
                            )
                            .size(topK),
                    Map.class
            );

            List<VectorSearchResultVO> results = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                Map<String, Object> source = hit.source();
                if (source != null) {
                    String content = (String) source.get("content");
                    String docId = (String) source.get("docId");

                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("docId", docId);
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
        try {
            Map<String, Object> doc = new HashMap<>();
            doc.put("docId", docId);
            doc.put("content", content);

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
        try {
            var bulkRequest = new co.elastic.clients.elasticsearch.core.BulkRequest.Builder();

            for (Map.Entry<String, String> entry : documents.entrySet()) {
                Map<String, Object> doc = new HashMap<>();
                doc.put("docId", entry.getKey());
                doc.put("content", entry.getValue());

                bulkRequest.operations(op -> op
                        .index(idx -> idx
                                .index(indexName)
                                .id(entry.getKey())
                                .document(doc)
                        )
                );
            }

            esClient.bulk(bulkRequest.build());
            log.info("Elasticsearch批量添加文档: count={}", documents.size());
        } catch (IOException e) {
            log.error("Elasticsearch批量添加文档失败", e);
        }
    }

    @Override
    public void removeDocument(String docId) {
        try {
            esClient.delete(d -> d
                    .index(indexName)
                    .id(docId)
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
