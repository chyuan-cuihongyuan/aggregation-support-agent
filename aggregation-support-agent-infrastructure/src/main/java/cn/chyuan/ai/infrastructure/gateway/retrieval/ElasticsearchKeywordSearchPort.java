package cn.chyuan.ai.infrastructure.gateway.retrieval;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.rag.adapter.port.IKeywordSearchPort;
import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 关键词检索端口实现 — Elasticsearch match 查询（工单 0163，W1 混合检索关键词路）
 * <p>
 * 与既有 {@link ElasticsearchBM25SearchService}（multiMatch + fuzziness 的 BM25 路）互补：
 * 本端口为纯 match 查询口径，供 rag.hybrid-enabled=true 时的混合检索编排调用。
 * <p>
 * 装配条件与既有 ES BM25 一致：ES 客户端就绪 + elasticsearch.enabled=true；
 * 任何检索异常内部降级为空列表，不抛出打断主链路。
 */
@Slf4j
@Service
@ConditionalOnClass(ElasticsearchClient.class)
@ConditionalOnBean(ElasticsearchClient.class)
@ConditionalOnProperty(name = "elasticsearch.enabled", havingValue = "true")
public class ElasticsearchKeywordSearchPort implements IKeywordSearchPort {

    @Autowired
    private ElasticsearchClient esClient;

    @Value("${elasticsearch.index.name}")
    private String indexName;

    @Override
    public List<VectorSearchResultVO> search(String query, int topK, TenantScopeVO scope) {
        if (query == null || query.isBlank() || topK <= 0) {
            return Collections.emptyList();
        }
        log.info("关键词路 ES match 检索: query={}, topK={}", query, topK);

        try {
            SearchResponse<Map> response = esClient.search(s -> s
                            .index(indexName)
                            .query(q -> q.bool(b -> {
                                // 纯 match 查询：content 字段词面匹配（区别于 BM25 路的 multiMatch+fuzziness）
                                b.must(m -> m.match(mm -> mm
                                        .field("content")
                                        .query(query)));
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
                if (source == null) {
                    continue;
                }
                Map<String, Object> metadata = new HashMap<>();
                // 与既有 ES BM25 路一致的元数据透传口径，保证 RRF 融合去重 key 可定位
                for (String key : new String[]{"docId", "documentId", "tenantId", "ownerUserId",
                        "knowledgeBaseId", "knowledgeBaseName", "chunkIndex"}) {
                    if (source.get(key) != null) {
                        metadata.put(key, source.get(key));
                    }
                }
                Object sourceName = source.get("source") != null ? source.get("source") : source.get("_source");
                if (sourceName != null) {
                    metadata.put("_source", sourceName);
                }
                Object fileName = source.get("fileName") != null ? source.get("fileName") : source.get("_file_name");
                if (fileName != null) {
                    metadata.put("_file_name", fileName);
                }
                metadata.put("retrievalType", "keyword");

                results.add(VectorSearchResultVO.builder()
                        .content((String) source.get("content"))
                        .score(hit.score() != null ? hit.score().floatValue() : 0f)
                        .metadata(metadata)
                        .build());
            }

            log.info("关键词路 ES match 检索完成: resultCount={}", results.size());
            return results;
        } catch (IOException e) {
            // 关键词路失败不阻断主链路：降级为空列表（等价单向量路行为）
            log.error("关键词路 ES match 检索失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public boolean isAvailable() {
        // ES 客户端由条件装配注入，非空即视为可用
        return esClient != null;
    }
}
