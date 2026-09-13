package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.workflow.model.DslCodec;
import cn.chyuan.ai.domain.workflow.model.GraphDiffCalculator;
import cn.chyuan.ai.domain.workflow.model.GraphDiffCalculator.GraphDiff;
import cn.chyuan.ai.domain.workflow.model.WorkflowGraph;
import cn.chyuan.ai.domain.workflow.service.BlueprintInstantiator;
import cn.chyuan.ai.domain.workflow.service.BlueprintInstantiator.Instantiated;
import cn.chyuan.ai.domain.workflow.service.BlueprintService;
import cn.chyuan.ai.domain.workflow.service.BlueprintService.BlueprintTemplate;
import cn.chyuan.ai.domain.workflow.service.GraphValidator;
import cn.chyuan.ai.domain.workflow.service.GraphValidator.ValidationResult;
import cn.chyuan.ai.types.enums.ResponseCode;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流蓝图与可视化端点（六期 AI 簇 0268-0276）—
 * 蓝图 CRUD（AI1）、实例化（AI6）、图版本 diff（AI8）、画布数据模型双向转换（AI2 后端侧，
 * 供无前端环境验证与契约断言基线）。编辑保存统一走 /workflow/validate（AI7 前端联动）。
 */
@Slf4j
@RestController
@CrossOrigin(origins = {"http://localhost:3000"})
@RequestMapping("/api/v1/workflow/blueprints")
public class BlueprintController {

    private final BlueprintService blueprintService;

    public BlueprintController(BlueprintService blueprintService) {
        this.blueprintService = blueprintService;
    }

    /** AI1：保存蓝图 */
    @PostMapping
    public Response<Map<String, Object>> save(@RequestBody BlueprintTemplate template) {
        try {
            BlueprintTemplate saved = blueprintService.save(template);
            return ok(toMap(saved));
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
    }

    /** AI1：更新蓝图 */
    @PutMapping("/{id}")
    public Response<Map<String, Object>> update(@PathVariable long id,
            @RequestBody BlueprintTemplate template) {
        try {
            BlueprintTemplate withId = new BlueprintTemplate(id, template.name(), template.description(),
                    template.category(), template.tags(), template.graphJson(), template.paramSchemaJson(),
                    template.operator());
            BlueprintTemplate updated = blueprintService.update(withId);
            return ok(toMap(updated));
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
    }

    /** AI1：删除蓝图 */
    @DeleteMapping("/{id}")
    public Response<Map<String, Object>> delete(@PathVariable long id) {
        try {
            blueprintService.delete(id);
            return ok(Map.of("deleted", id));
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
    }

    /** AI1：蓝图清单（可按分类/标签过滤） */
    @GetMapping
    public Response<List<Map<String, Object>>> list(@RequestParam(required = false) String category,
            @RequestParam(required = false) String tag) {
        List<BlueprintTemplate> templates = category != null && !category.isBlank()
                ? blueprintService.listByCategory(category)
                : tag != null && !tag.isBlank()
                        ? blueprintService.listByTag(tag)
                        : blueprintService.listAll();
        return ok(templates.stream().map(BlueprintController::toMap).toList());
    }

    /** AI1：蓝图详情 */
    @GetMapping("/{id}")
    public Response<Map<String, Object>> get(@PathVariable long id) {
        try {
            return ok(toMap(blueprintService.get(id)));
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
    }

    /** AI6：蓝图实例化（模板 → 图定义副本 + 参数替换报告，不自动注册） */
    @PostMapping("/{id}/instantiate")
    public Response<Map<String, Object>> instantiate(@PathVariable long id,
            @RequestBody Map<String, String> params) {
        try {
            BlueprintTemplate template = blueprintService.get(id);
            Instantiated result = BlueprintInstantiator.instantiate(template.graphJson(),
                    params, template.paramSchemaJson());
            ValidationResult validation = GraphValidator.validate(result.graph());
            Map<String, Object> out = new HashMap<>();
            out.put("graphJson", result.graphJson());
            out.put("replacements", result.replacements());
            out.put("valid", validation.valid());
            out.put("errors", validation.errors());
            return ok(out);
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
    }

    /** AI8：图版本 diff（两份 DSL JSON 比对） */
    @PostMapping("/diff")
    public Response<Map<String, Object>> diff(@RequestBody Map<String, String> body) {
        try {
            WorkflowGraph before = DslCodec.importDsl(body.get("before"));
            WorkflowGraph after = DslCodec.importDsl(body.get("after"));
            GraphDiff diff = GraphDiffCalculator.diff(before, after);
            Map<String, Object> out = new HashMap<>();
            out.put("nodes", diff.nodes());
            out.put("edges", diff.edges());
            out.put("added", diff.added());
            out.put("removed", diff.removed());
            out.put("changed", diff.changed());
            out.put("unchanged", diff.unchanged());
            return ok(out);
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
    }

    /** AI2：画布数据模型转换（DSL → nodes/edges 画布模型，位置缺省由前端自动布局） */
    @PostMapping("/canvas/convert")
    public Response<Map<String, Object>> toCanvas(@RequestBody Map<String, String> body) {
        try {
            WorkflowGraph graph = DslCodec.importDsl(body.get("graphJson"));
            Map<String, Object> canvas = new HashMap<>();
            List<Map<String, Object>> nodes = graph.nodes().stream()
                    .map(n -> {
                        Map<String, Object> node = new HashMap<>();
                        node.put("id", n.id());
                        node.put("type", n.type());
                        node.put("params", n.config());
                        return node;
                    }).toList();
            List<Map<String, Object>> edges = graph.edges().stream()
                    .map(e -> {
                        Map<String, Object> edge = new HashMap<>();
                        edge.put("id", e.from() + "->" + e.to());
                        edge.put("source", e.from());
                        edge.put("target", e.to());
                        return edge;
                    }).toList();
            canvas.put("name", graph.name());
            canvas.put("nodes", nodes);
            canvas.put("edges", edges);
            return ok(canvas);
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
    }

    /** 统一成功响应（聚合仓 Response 为 builder 形态） */
    private static <T> Response<T> ok(T data) {
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info("成功").data(data).build();
    }

    private static Response<Map<String, Object>> fail(String message) {
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info(message).build();
    }

    private static Map<String, Object> toMap(BlueprintTemplate template) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", template.id());
        map.put("name", template.name());
        map.put("description", template.description());
        map.put("category", template.category());
        map.put("tags", template.tags());
        map.put("graphJson", template.graphJson());
        map.put("paramSchemaJson", template.paramSchemaJson());
        map.put("operator", template.operator());
        map.put("parsed", safeParse(template.graphJson()));
        return map;
    }

    private static Map<String, Object> safeParse(String graphJson) {
        try {
            return JSON.parseObject(graphJson);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
