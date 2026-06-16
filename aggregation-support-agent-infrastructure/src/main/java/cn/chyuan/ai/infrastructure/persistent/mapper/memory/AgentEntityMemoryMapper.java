package cn.chyuan.ai.infrastructure.persistent.mapper.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import cn.chyuan.ai.infrastructure.persistent.po.memory.AgentEntityMemoryPO;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 实体记忆 Mapper
 */
@Mapper
public interface AgentEntityMemoryMapper extends BaseMapper<AgentEntityMemoryPO> {
    
    @Select("SELECT * FROM agent_entity_memory WHERE entity_id = #{entityId} AND status = 1")
    AgentEntityMemoryPO selectByEntityId(@Param("entityId") String entityId);
    
    @Select("SELECT * FROM agent_entity_memory WHERE tenant_id = #{tenantId} AND user_id = #{userId} AND status = 1")
    List<AgentEntityMemoryPO> selectByTenantAndUser(@Param("tenantId") String tenantId, @Param("userId") String userId);
    
    @Select("SELECT * FROM agent_entity_memory WHERE tenant_id = #{tenantId} AND user_id = #{userId} AND entity_type = #{entityType} AND status = 1")
    List<AgentEntityMemoryPO> selectByType(@Param("tenantId") String tenantId, @Param("userId") String userId, @Param("entityType") String entityType);
    
    @Update("UPDATE agent_entity_memory SET status = 0, updated_at = NOW() WHERE entity_id = #{entityId}")
    int softDelete(@Param("entityId") String entityId);
    
    @Select("SELECT * FROM agent_entity_memory WHERE tenant_id = #{tenantId} AND user_id = #{userId} AND entity_name = #{entityName} AND status = 1 LIMIT 1")
    AgentEntityMemoryPO selectByName(@Param("tenantId") String tenantId, @Param("userId") String userId, @Param("entityName") String entityName);
}
