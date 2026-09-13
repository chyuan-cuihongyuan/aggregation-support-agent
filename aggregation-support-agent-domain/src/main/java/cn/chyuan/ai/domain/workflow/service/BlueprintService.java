package cn.chyuan.ai.domain.workflow.service;

import cn.chyuan.ai.domain.workflow.model.DslCodec;
import cn.chyuan.ai.domain.workflow.model.WorkflowGraph;
import cn.chyuan.ai.domain.workflow.service.GraphValidator.ValidationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 蓝图模板服务（工单 0268 AI1，借鉴 Kestra blueprints）—
 * 常用编排固化为模板：名称/描述/分类/标签 + 图定义 DSL + 参数 schema；
 * 保存前经 DslCodec 解析与 GraphValidator 校验（坏模板入库即拒）。
 * 存储经 {@link BlueprintStore} 端口（infrastructure 落 workflow_blueprint 聚合库第 16 表）。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class BlueprintService {

    /** 蓝图模板值对象 */
    public record BlueprintTemplate(Long id, String name, String description, String category,
            Set<String> tags, String graphJson, String paramSchemaJson, String operator) {

        public BlueprintTemplate {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("蓝图名不能为空");
            }
            if (category == null || category.isBlank()) {
                category = "general";
            }
            tags = tags == null ? Set.of() : Set.copyOf(tags);
        }
    }

    /** 蓝图持久化端口（infrastructure 经 MyBatis 落 workflow_blueprint 表） */
    public interface BlueprintStore {

        void insert(BlueprintTemplate template);

        void update(BlueprintTemplate template);

        void deleteById(long id);

        BlueprintTemplate findById(long id);

        List<BlueprintTemplate> listAll();
    }

    private final BlueprintStore store;

    public BlueprintService(BlueprintStore store) {
        this.store = store;
    }

    /** 保存（新增）：模板图必须可解析且通过 GraphValidator */
    public BlueprintTemplate save(BlueprintTemplate template) {
        validateGraph(template.graphJson(), template.paramSchemaJson());
        long next = store.listAll().stream().mapToLong(t -> t.id() == null ? 0 : t.id()).max().orElse(0) + 1;
        BlueprintTemplate withId = new BlueprintTemplate(next, template.name(), template.description(),
                template.category(), template.tags(), template.graphJson(), template.paramSchemaJson(),
                template.operator());
        store.insert(withId);
        log.info("蓝图保存: id={} name={} category={}", withId.id(), withId.name(), withId.category());
        return withId;
    }

    /** 更新（同样新校验） */
    public BlueprintTemplate update(BlueprintTemplate template) {
        if (template.id() == null || store.findById(template.id()) == null) {
            throw new IllegalArgumentException("蓝图不存在: " + template.id());
        }
        validateGraph(template.graphJson(), template.paramSchemaJson());
        store.update(template);
        return template;
    }

    public void delete(long id) {
        if (store.findById(id) == null) {
            throw new IllegalArgumentException("蓝图不存在: " + id);
        }
        store.deleteById(id);
    }

    public BlueprintTemplate get(long id) {
        BlueprintTemplate template = store.findById(id);
        if (template == null) {
            throw new IllegalArgumentException("蓝图不存在: " + id);
        }
        return template;
    }

    public List<BlueprintTemplate> listAll() {
        return store.listAll().stream()
                .sorted(Comparator.comparing(BlueprintTemplate::name))
                .toList();
    }

    /** 按分类检索 */
    public List<BlueprintTemplate> listByCategory(String category) {
        return listAll().stream().filter(t -> t.category().equals(category)).toList();
    }

    /** 按标签检索（任一命中） */
    public List<BlueprintTemplate> listByTag(String tag) {
        return listAll().stream().filter(t -> t.tags().contains(tag)).toList();
    }

    private void validateGraph(String graphJson, String paramSchemaJson) {
        WorkflowGraph graph = DslCodec.importDsl(graphJson);
        ValidationResult result = GraphValidator.validate(graph);
        if (!result.valid()) {
            throw new IllegalArgumentException("蓝图图定义校验失败: " + String.join("; ", result.errors()));
        }
        if (paramSchemaJson != null && !paramSchemaJson.isBlank()) {
            BlueprintInstantiator.parseSchema(paramSchemaJson);
        }
    }
}
