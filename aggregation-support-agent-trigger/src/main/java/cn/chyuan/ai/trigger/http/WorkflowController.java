package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.workflow.adapter.port.IWorkflowRunStore;
import cn.chyuan.ai.domain.workflow.model.DslCodec;
import cn.chyuan.ai.domain.workflow.model.WorkflowGraph;
import cn.chyuan.ai.domain.workflow.service.GraphExecutor;
import cn.chyuan.ai.domain.workflow.service.GraphExpander;
import cn.chyuan.ai.domain.workflow.service.GraphValidator;
import cn.chyuan.ai.domain.workflow.service.InMemoryCheckpointStore;
import cn.chyuan.ai.domain.workflow.service.InMemoryWorkflowRunStore;
import cn.chyuan.ai.domain.workflow.service.InterruptRegistry;
import cn.chyuan.ai.domain.workflow.service.WorkflowContext;
import cn.chyuan.ai.domain.workflow.service.WorkflowRegistry;
import cn.chyuan.ai.domain.workflow.service.WorkflowRunRecord;
import cn.chyuan.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流编排端点（五期 AB 簇 0204-0212）—
 * POST /api/v1/workflow/validate（DSL 校验）、POST /api/v1/workflow/register（DSL 注册多版本）、
 * POST /api/v1/workflow/run（按租户路由选版执行）、POST /api/v1/workflow/resume（中断恢复）、
 * GET /api/v1/workflow/runs/{runId}、GET /api/v1/workflow/runs（最近运行）。
 * 节点执行器为最小内置语义（echo：回显输入），演示引擎全链路；业务节点经 NodeExecutor
 * 函数式扩展点接入（后续工单/业务方注入）。
 */
@Slf4j
@RestController
@CrossOrigin(origins = {"http://localhost:3000"})
@RequestMapping("/api/v1/workflow")
public class WorkflowController {

    private final WorkflowRegistry registry = new WorkflowRegistry();
    private final InterruptRegistry interruptRegistry = new InterruptRegistry();
    private final InMemoryCheckpointStore checkpointStore = new InMemoryCheckpointStore();
    private final GraphExecutor executor = new GraphExecutor(checkpointStore, interruptRegistry, ms -> {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    });
    private final IWorkflowRunStore runStore;

    public WorkflowController(@Autowired(required = false) IWorkflowRunStore runStore) {
        // MyBatis 仓储可用则落库；否则内存兜底（单机演示/测试）
        this.runStore = runStore == null ? new InMemoryWorkflowRunStore() : runStore;
    }

    /** AB1/AB7：DSL 结构校验（不入注册表） */
    @PostMapping("/validate")
    public Response<Map<String, Object>> validate(@RequestBody Map<String, Object> body) {
        String dsl = str(body, "dsl");
        if (dsl == null || dsl.isBlank()) {
            return fail("dsl 不能为空");
        }
        try {
            WorkflowGraph graph = DslCodec.importDsl(dsl);
            GraphValidator.ValidationResult result = GraphValidator.validate(graph);
            Map<String, Object> out = new HashMap<>();
            out.put("valid", result.valid());
            out.put("errors", result.errors());
            out.put("nodeCount", graph.nodes().size());
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.SUCCESS.getCode()).info("成功").data(out).build();
        } catch (IllegalArgumentException e) {
            Map<String, Object> out = new HashMap<>();
            out.put("valid", false);
            out.put("errors", List.of(e.getMessage()));
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.SUCCESS.getCode()).info("成功").data(out).build();
        }
    }

    /** AB7/AB8：DSL 导入并注册为指定版本（canaryPercentage 供租户灰度切流） */
    @PostMapping("/register")
    public Response<Map<String, Object>> register(@RequestBody Map<String, Object> body) {
        String dsl = str(body, "dsl");
        int version = body.get("version") == null ? 1 : Integer.parseInt(String.valueOf(body.get("version")));
        int canary = body.get("canaryPercentage") == null
                ? 0 : Integer.parseInt(String.valueOf(body.get("canaryPercentage")));
        try {
            WorkflowGraph graph = DslCodec.importDsl(dsl);
            registry.register(graph, version, canary);
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.SUCCESS.getCode()).info("成功")
                    .data(Map.of("name", graph.name(), "version", version, "canaryPercentage", canary))
                    .build();
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
    }

    /** AB2/AB8/AB9：按租户路由选版 → 子图展开 → 执行 → 运行历史落档 */
    @PostMapping("/run")
    public Response<Map<String, Object>> run(@RequestBody Map<String, Object> body) {
        String name = str(body, "name");
        String tenantId = str(body, "tenantId");
        WorkflowRegistry.VersionedGraph picked = registry.route(name, tenantId);
        if (picked == null) {
            return fail("工作流未注册: " + name);
        }
        try {
            WorkflowGraph graph = new GraphExpander(Map.of()).expand(picked.graph());
            @SuppressWarnings("unchecked")
            Map<String, Object> input = body.get("input") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            long start = System.currentTimeMillis();
            GraphExecutor.ExecutionReport report = executor.execute(graph, input, WorkflowController::echo);
            WorkflowRunRecord record = WorkflowRunRecord.from(report, picked.graph(),
                    picked.version(), tenantId, System.currentTimeMillis() - start);
            runStore.saveRun(record);
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.SUCCESS.getCode()).info("成功").data(record.toMap()).build();
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
    }

    /** AB4：人工中断恢复（token + 人工输入） */
    @PostMapping("/resume")
    public Response<Map<String, Object>> resume(@RequestBody Map<String, Object> body) {
        String token = str(body, "token");
        String userInput = str(body, "userInput");
        try {
            GraphExecutor.ExecutionReport report = executor.resume(interruptRegistry, token, userInput,
                    WorkflowController::echo);
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.SUCCESS.getCode()).info("成功")
                    .data(Map.of("runId", report.runId(), "status", report.status())).build();
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
    }

    @GetMapping("/runs/{runId}")
    public Response<Map<String, Object>> runDetail(@PathVariable String runId) {
        WorkflowRunRecord record = runStore.findRun(runId);
        if (record == null) {
            return fail("运行不存在: " + runId);
        }
        Map<String, Object> out = new HashMap<>(record.toMap());
        out.put("nodeRuns", record.nodeRuns());
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode()).info("成功").data(out).build();
    }

    @GetMapping("/runs")
    public Response<List<Map<String, Object>>> recentRuns(@RequestParam(required = false) String name,
            @RequestParam(defaultValue = "20") int limit) {
        return Response.<List<Map<String, Object>>>builder()
                .code(ResponseCode.SUCCESS.getCode()).info("成功")
                .data(runStore.recentRuns(name, limit).stream().map(WorkflowRunRecord::toMap).toList())
                .build();
    }

    /** 内置最小节点语义：回显输入（引擎演示/测试）；真实业务节点后续经 NodeExecutor 接入 */
    private static String echo(WorkflowGraph.NodeSpec node, WorkflowContext ctx) {
        return "echo:" + node.id();
    }

    private static String str(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Response<Map<String, Object>> fail(String message) {
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info(message).build();
    }
}
