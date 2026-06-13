package cn.chyuan.ai.infrastructure.persistent.mapper;

import cn.chyuan.ai.infrastructure.dao.po.ExtractionTaskPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 图谱构建任务 Mapper
 */
@Mapper
public interface ExtractionTaskMapper {

    /** 插入任务 */
    void insert(ExtractionTaskPO record);

    /** 根据任务ID查询 */
    ExtractionTaskPO queryByTaskId(@Param("taskId") String taskId);

    /** 根据文档ID查询 */
    ExtractionTaskPO queryByDocumentId(@Param("documentId") String documentId);

    /** 查询所有卡在 PROCESSING 状态的任务 */
    List<ExtractionTaskPO> queryByStatus(@Param("status") String status);

    /** 更新任务状态 */
    void updateStatus(@Param("taskId") String taskId,
                      @Param("status") String status,
                      @Param("processedChunks") Integer processedChunks,
                      @Param("extractedEntities") Integer extractedEntities,
                      @Param("extractedRelations") Integer extractedRelations,
                      @Param("errorMessage") String errorMessage,
                      @Param("updateTime") java.util.Date updateTime);
}
