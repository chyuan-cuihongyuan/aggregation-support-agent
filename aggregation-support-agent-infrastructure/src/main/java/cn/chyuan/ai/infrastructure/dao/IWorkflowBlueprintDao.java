package cn.chyuan.ai.infrastructure.dao;

import cn.chyuan.ai.infrastructure.dao.po.WorkflowBlueprintPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 工作流蓝图模板 DAO（工单 0268 AI1）
 */
@Mapper
public interface IWorkflowBlueprintDao {

    int insert(WorkflowBlueprintPO po);

    int update(WorkflowBlueprintPO po);

    int deleteById(@Param("id") Long id);

    WorkflowBlueprintPO queryById(@Param("id") Long id);

    List<WorkflowBlueprintPO> queryAll();
}
