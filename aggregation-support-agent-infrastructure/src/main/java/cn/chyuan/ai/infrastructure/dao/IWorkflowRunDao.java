package cn.chyuan.ai.infrastructure.dao;

import cn.chyuan.ai.infrastructure.dao.po.WorkflowRunPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 工作流运行 DAO（工单 0212 AB9；双方言公共子集 SQL）
 */
@Mapper
public interface IWorkflowRunDao {

    int insert(WorkflowRunPO po);

    WorkflowRunPO queryByRunId(@Param("runId") String runId);

    List<WorkflowRunPO> queryRecent(@Param("workflowName") String workflowName, @Param("limit") int limit);
}
