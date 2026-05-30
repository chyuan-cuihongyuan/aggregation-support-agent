package cn.chyuan.ai.infrastructure.persistent.mapper.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import cn.chyuan.ai.infrastructure.dao.po.memory.AgentMemoryPO;

import java.util.List;
import java.util.Map;

/**
 * Agent 记忆 Mapper
 */
@Mapper
public interface AgentMemoryMapper extends BaseMapper<AgentMemoryPO> {
    
    /**
     * 根据内容哈希检查是否存在
     */
    @Select("SELECT COUNT(*) FROM agent_memory " +
            "WHERE content_hash = #{contentHash} " +
            "AND tenant_id = #{tenantId} " +
            "AND user_id = #{userId} " +
            "AND status = 1")
    int countByContentHash(@Param("contentHash") String contentHash,
                           @Param("tenantId") String tenantId,
                           @Param("userId") String userId);
    
    /**
     * 软删除记忆
     */
    @Update("UPDATE agent_memory SET status = 0, updated_at = NOW() " +
            "WHERE memory_id = #{memoryId}")
    int softDelete(@Param("memoryId") String memoryId);
    
    /**
     * 更新记忆内容
     */
    @Update("UPDATE agent_memory SET content = #{content}, " +
            "content_hash = #{contentHash}, updated_at = NOW() " +
            "WHERE memory_id = #{memoryId}")
    int updateContent(@Param("memoryId") String memoryId,
                      @Param("content") String content,
                      @Param("contentHash") String contentHash);
    
    /**
     * 删除过期记忆
     */
    @Delete("DELETE FROM agent_memory " +
            "WHERE tenant_id = #{tenantId} " +
            "AND user_id = #{userId} " +
            "AND expires_at IS NOT NULL " +
            "AND expires_at < NOW()")
    int deleteExpired(@Param("tenantId") String tenantId,
                      @Param("userId") String userId);

    /**
     * 查询所有有效的租户-用户对（去重）
     */
    @Select("SELECT DISTINCT tenant_id, user_id FROM agent_memory WHERE status = 1")
    List<Map<String, Object>> selectDistinctTenantUserPairs();
}
