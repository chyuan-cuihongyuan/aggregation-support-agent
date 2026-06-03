package cn.chyuan.ai.infrastructure.persistent.mapper.memory;

import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import cn.chyuan.ai.infrastructure.dao.po.memory.AgentMemoryPO;

import java.util.List;
import java.util.Map;

/**
 * Agent 记忆 Mapper
 */
@Mapper
public interface AgentMemoryMapper {

    String SELECT_COLUMNS = """
            id, memory_id, tenant_id, user_id, agent_id, session_id, content, content_hash,
            memory_type, scope, importance, source, metadata, status, expires_at, created_at, updated_at
            """;

    @Insert("""
            INSERT INTO agent_memory (
                memory_id, tenant_id, user_id, agent_id, session_id, content, content_hash,
                memory_type, scope, importance, source, metadata, status, expires_at, created_at, updated_at
            ) VALUES (
                #{memoryId},
                COALESCE(#{tenantId}, ''),
                COALESCE(#{userId}, ''),
                COALESCE(#{agentId}, ''),
                #{sessionId},
                COALESCE(#{content}, ''),
                COALESCE(#{contentHash}, ''),
                COALESCE(#{memoryType}, 'FACT'),
                COALESCE(#{scope}, '/'),
                COALESCE(#{importance}, 0.5),
                #{source},
                #{metadata,typeHandler=com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler},
                COALESCE(#{status}, 1),
                #{expiresAt},
                COALESCE(#{createdAt}, NOW()),
                COALESCE(#{updatedAt}, NOW())
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AgentMemoryPO record);

    @Results(id = "AgentMemoryResultMap", value = {
            @Result(column = "id", property = "id", id = true),
            @Result(column = "memory_id", property = "memoryId"),
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "agent_id", property = "agentId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "content", property = "content"),
            @Result(column = "content_hash", property = "contentHash"),
            @Result(column = "memory_type", property = "memoryType"),
            @Result(column = "scope", property = "scope"),
            @Result(column = "importance", property = "importance"),
            @Result(column = "source", property = "source"),
            @Result(column = "metadata", property = "metadata", typeHandler = JacksonTypeHandler.class),
            @Result(column = "status", property = "status"),
            @Result(column = "expires_at", property = "expiresAt"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    @Select("SELECT " + SELECT_COLUMNS + " FROM agent_memory WHERE memory_id = #{memoryId} AND status = 1 LIMIT 1")
    AgentMemoryPO selectActiveByMemoryId(@Param("memoryId") String memoryId);

    @ResultMap("AgentMemoryResultMap")
    @Select("SELECT " + SELECT_COLUMNS + " FROM agent_memory " +
            "WHERE tenant_id = #{tenantId} AND user_id = #{userId} AND status = 1 " +
            "ORDER BY created_at DESC")
    List<AgentMemoryPO> selectActiveByTenantAndUser(@Param("tenantId") String tenantId,
                                                    @Param("userId") String userId);

    @ResultMap("AgentMemoryResultMap")
    @Select("SELECT " + SELECT_COLUMNS + " FROM agent_memory " +
            "WHERE scope LIKE CONCAT(#{scope}, '%') AND status = 1 " +
            "ORDER BY created_at DESC")
    List<AgentMemoryPO> selectActiveByScope(@Param("scope") String scope);

    @ResultMap("AgentMemoryResultMap")
    @Select("""
            <script>
            SELECT """ + SELECT_COLUMNS + """
            FROM agent_memory
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND status = 1
            <if test="scope != null and scope != ''">
              AND scope LIKE CONCAT(#{scope}, '%')
            </if>
            ORDER BY created_at DESC
            LIMIT #{limit}
            </script>
            """)
    List<AgentMemoryPO> selectRecentByTenantUserScope(@Param("tenantId") String tenantId,
                                                      @Param("userId") String userId,
                                                      @Param("scope") String scope,
                                                      @Param("limit") int limit);
    
    /**
     * 根据内容哈希检查是否存在
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM agent_memory
            WHERE content_hash = #{contentHash}
              AND tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND status = 1
            <if test="scope != null and scope != ''">
              AND scope = #{scope}
            </if>
            </script>
            """)
    int countByContentHash(@Param("contentHash") String contentHash,
                           @Param("tenantId") String tenantId,
                           @Param("userId") String userId,
                           @Param("scope") String scope);
    
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
