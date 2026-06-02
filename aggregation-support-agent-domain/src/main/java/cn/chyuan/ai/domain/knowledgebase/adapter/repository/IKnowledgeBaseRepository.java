package cn.chyuan.ai.domain.knowledgebase.adapter.repository;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.knowledgebase.model.entity.KnowledgeBaseEntity;

import java.util.List;

public interface IKnowledgeBaseRepository {

    void save(KnowledgeBaseEntity entity);

    List<KnowledgeBaseEntity> queryByScope(TenantScopeVO scope);

    KnowledgeBaseEntity queryById(String knowledgeBaseId, TenantScopeVO scope);

    void markDeleted(String knowledgeBaseId, TenantScopeVO scope);
}
