package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.workflow.service.BlueprintService;
import cn.chyuan.ai.domain.workflow.service.BlueprintService.BlueprintTemplate;
import cn.chyuan.ai.infrastructure.dao.IWorkflowBlueprintDao;
import cn.chyuan.ai.infrastructure.dao.po.WorkflowBlueprintPO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工作流蓝图仓储实现（工单 0268 AI1）：实现 {@link BlueprintService.BlueprintStore} 端口，
 * 经 MyBatis 落 workflow_blueprint 表（双方言公共子集 SQL）。
 *
 * @author chyuan
 */
@Repository
public class WorkflowBlueprintRepository implements BlueprintService.BlueprintStore {

    @Resource
    private IWorkflowBlueprintDao dao;

    @Override
    public void insert(BlueprintTemplate template) {
        dao.insert(toPo(template));
    }

    @Override
    public void update(BlueprintTemplate template) {
        dao.update(toPo(template));
    }

    @Override
    public void deleteById(long id) {
        dao.deleteById(id);
    }

    @Override
    public BlueprintTemplate findById(long id) {
        return toDomain(dao.queryById(id));
    }

    @Override
    public List<BlueprintTemplate> listAll() {
        return dao.queryAll().stream().map(WorkflowBlueprintRepository::toDomain).toList();
    }

    private static WorkflowBlueprintPO toPo(BlueprintTemplate template) {
        WorkflowBlueprintPO po = new WorkflowBlueprintPO();
        po.setId(template.id());
        po.setName(template.name());
        po.setDescription(template.description());
        po.setCategory(template.category());
        po.setTags(String.join(",", template.tags()));
        po.setGraphJson(template.graphJson());
        po.setParamSchemaJson(template.paramSchemaJson());
        po.setOperator(template.operator());
        po.setUpdateTime(new java.util.Date());
        return po;
    }

    private static BlueprintTemplate toDomain(WorkflowBlueprintPO po) {
        if (po == null) {
            return null;
        }
        Set<String> tags = po.getTags() == null || po.getTags().isBlank()
                ? Set.of()
                : Arrays.stream(po.getTags().split(",")).map(String::trim).collect(Collectors.toSet());
        return new BlueprintTemplate(po.getId(), po.getName(), po.getDescription(), po.getCategory(),
                tags, po.getGraphJson(), po.getParamSchemaJson(), po.getOperator());
    }
}
