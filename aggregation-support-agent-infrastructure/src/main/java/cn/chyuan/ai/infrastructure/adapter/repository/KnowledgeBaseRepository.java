package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.knowledgebase.adapter.repository.IKnowledgeBaseRepository;
import cn.chyuan.ai.domain.knowledgebase.model.entity.KnowledgeBaseEntity;
import cn.chyuan.ai.infrastructure.dao.po.KnowledgeBasePO;
import cn.chyuan.ai.infrastructure.persistent.mapper.KnowledgeBaseMapper;
import org.springframework.stereotype.Repository;

import jakarta.annotation.Resource;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Repository
public class KnowledgeBaseRepository implements IKnowledgeBaseRepository {

    @Resource
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Override
    public void save(KnowledgeBaseEntity entity) {
        Date now = new Date();
        KnowledgeBasePO po = KnowledgeBasePO.builder()
                .knowledgeBaseId(entity.getKnowledgeBaseId())
                .tenantId(entity.getTenantId())
                .ownerUserId(entity.getOwnerUserId())
                .name(entity.getName())
                .description(entity.getDescription() != null ? entity.getDescription() : "")
                .icon(entity.getIcon() != null ? entity.getIcon() : "")
                .color(entity.getColor() != null ? entity.getColor() : "")
                .deletedFlag(entity.getDeletedFlag() != null ? entity.getDeletedFlag() : 0)
                .createTime(entity.getCreateTime() != null ? entity.getCreateTime() : now)
                .updateTime(now)
                .build();
        knowledgeBaseMapper.insert(po);
    }

    @Override
    public List<KnowledgeBaseEntity> queryByScope(TenantScopeVO scope) {
        return knowledgeBaseMapper.queryByScope(scope).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public KnowledgeBaseEntity queryById(String knowledgeBaseId, TenantScopeVO scope) {
        KnowledgeBasePO po = knowledgeBaseMapper.queryByIdAndScope(knowledgeBaseId, scope);
        return po != null ? toEntity(po) : null;
    }

    @Override
    public void markDeleted(String knowledgeBaseId, TenantScopeVO scope) {
        knowledgeBaseMapper.markDeleted(knowledgeBaseId, scope);
    }

    private KnowledgeBaseEntity toEntity(KnowledgeBasePO po) {
        return KnowledgeBaseEntity.builder()
                .id(po.getId())
                .knowledgeBaseId(po.getKnowledgeBaseId())
                .tenantId(po.getTenantId())
                .ownerUserId(po.getOwnerUserId())
                .name(po.getName())
                .description(po.getDescription())
                .icon(po.getIcon())
                .color(po.getColor())
                .deletedFlag(po.getDeletedFlag())
                .documentCount(po.getDocumentCount())
                .createTime(po.getCreateTime())
                .updateTime(po.getUpdateTime())
                .build();
    }
}
