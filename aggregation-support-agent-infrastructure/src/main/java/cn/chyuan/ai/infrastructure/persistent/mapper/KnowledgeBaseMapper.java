package cn.chyuan.ai.infrastructure.persistent.mapper;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.infrastructure.dao.po.KnowledgeBasePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface KnowledgeBaseMapper {
    void insert(KnowledgeBasePO record);

    List<KnowledgeBasePO> queryByScope(@Param("scope") TenantScopeVO scope);

    KnowledgeBasePO queryByIdAndScope(@Param("knowledgeBaseId") String knowledgeBaseId,
                                      @Param("scope") TenantScopeVO scope);

    void markDeleted(@Param("knowledgeBaseId") String knowledgeBaseId,
                     @Param("scope") TenantScopeVO scope);
}
