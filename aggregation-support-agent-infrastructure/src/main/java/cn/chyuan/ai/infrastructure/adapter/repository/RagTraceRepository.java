package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.rag.adapter.repository.IRagTraceRepository;
import cn.chyuan.ai.domain.rag.model.entity.RagTraceEntity;
import cn.chyuan.ai.domain.rag.model.valobj.RagSourceVO;
import cn.chyuan.ai.infrastructure.dao.po.RagTracePO;
import cn.chyuan.ai.infrastructure.persistent.mapper.RagTraceMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 检索追踪仓储实现 — 写入 rag_trace 表，按租户作用域查询
 */
@Slf4j
@Repository("ragTraceRepository")
public class RagTraceRepository implements IRagTraceRepository {

    @Resource
    private RagTraceMapper ragTraceMapper;

    @Resource
    private ObjectMapper objectMapper;

    @Override
    public void save(RagTraceEntity entity) {
        // sources 列表序列化为 JSON 字符串落库；空列表也序列化成 "[]"
        String sourceDocsJson = serializeSources(entity.getSources());
        RagTracePO po = RagTracePO.builder()
                .traceId(entity.getTraceId())
                .tenantId(entity.getTenantId())
                .ownerUserId(entity.getOwnerUserId())
                .sessionId(entity.getSessionId() != null ? entity.getSessionId() : "")
                .agentId(entity.getAgentId() != null ? entity.getAgentId() : "")
                .queryText(entity.getQueryText())
                .rewriteText(entity.getRewriteText())
                .retrievalTopk(entity.getRetrievalTopk() != null ? entity.getRetrievalTopk() : 0)
                .sourceDocs(sourceDocsJson)
                .answerScore(entity.getAnswerScore())
                .hallucinationScore(entity.getHallucinationScore())
                .createTime(entity.getCreateTime() != null ? entity.getCreateTime() : new Date())
                .build();
        ragTraceMapper.insert(po);
    }

    @Override
    public RagTraceEntity queryByTraceId(String traceId, TenantScopeVO scope) {
        RagTracePO po = ragTraceMapper.selectByTraceId(traceId, scope.getTenantId(), scope.getOwnerUserId());
        return po == null ? null : toEntity(po);
    }

    @Override
    public List<RagTraceEntity> queryBySessionId(String sessionId, TenantScopeVO scope) {
        List<RagTracePO> poList = ragTraceMapper.selectBySessionId(sessionId, scope.getTenantId(), scope.getOwnerUserId());
        return poList.stream().map(this::toEntity).collect(Collectors.toList());
    }

    /** 将命中证据列表序列化为 JSON 字符串；失败时降级为 "[]" */
    private String serializeSources(List<RagSourceVO> sources) {
        if (sources == null || sources.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (Exception e) {
            log.warn("RAG 检索证据序列化失败，降级为空数组: {}", e.getMessage());
            return "[]";
        }
    }

    /** 将 JSON 字符串反序列化为命中证据列表；失败时降级为空列表 */
    private List<RagSourceVO> deserializeSources(String sourceDocsJson) {
        if (sourceDocsJson == null || sourceDocsJson.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(sourceDocsJson, new TypeReference<List<RagSourceVO>>() {});
        } catch (Exception e) {
            log.warn("RAG 检索证据反序列化失败，降级为空列表: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private RagTraceEntity toEntity(RagTracePO po) {
        return RagTraceEntity.builder()
                .id(po.getId())
                .traceId(po.getTraceId())
                .tenantId(po.getTenantId())
                .ownerUserId(po.getOwnerUserId())
                .sessionId(po.getSessionId())
                .agentId(po.getAgentId())
                .queryText(po.getQueryText())
                .rewriteText(po.getRewriteText())
                .retrievalTopk(po.getRetrievalTopk())
                .sources(deserializeSources(po.getSourceDocs()))
                .answerScore(po.getAnswerScore())
                .hallucinationScore(po.getHallucinationScore())
                .createTime(po.getCreateTime())
                .build();
    }
}
