package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.research.model.valobj.ResearchReportVO;
import cn.chyuan.ai.domain.research.service.ReportExporter;
import cn.chyuan.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 深度研究控制器（工单 0353/0355 AR7/AR9）。
 * 研究任务端点清单（契约对账基线，见聚合 web research-contract.test.ts）：
 * POST /api/v1/research/tasks、GET /api/v1/research/tasks、
 * GET /api/v1/research/tasks/{taskId}、GET /api/v1/research/tasks/{taskId}/report、
 * POST /api/v1/research/tasks/{taskId}/export。
 * research.enabled 默认关，开启才注册；任务态为进程内演示实现（纯函数内核）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/research")
@ConditionalOnProperty(name = "research.enabled", havingValue = "true")
public class ResearchController {

    private final Map<String, ResearchReportVO> reports = new ConcurrentHashMap<>();

    /** 创建研究任务（返回任务ID） */
    @PostMapping("/tasks")
    public Response<String> createTask(@RequestParam String topic,
                                       @RequestParam(defaultValue = "technical,business,risk") String perspectives) {
        try {
            String taskId = "rt-" + System.currentTimeMillis();
            return Response.<String>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info("研究任务已受理")
                    .data(taskId)
                    .build();
        } catch (Exception e) {
            log.error("研究任务创建失败", e);
            return Response.<String>builder().code(ResponseCode.UN_ERROR.getCode())
                    .info("研究任务创建失败: " + e.getMessage()).build();
        }
    }

    /** 任务列表（演示态返回任务ID清单） */
    @GetMapping("/tasks")
    public Response<List<String>> listTasks() {
        return Response.<List<String>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(List.copyOf(reports.keySet()))
                .build();
    }

    /** 任务详情（状态摘要） */
    @GetMapping("/tasks/{taskId}")
    public Response<Map<String, Object>> taskDetail(@PathVariable String taskId) {
        ResearchReportVO report = reports.get(taskId);
        if (report == null) {
            return Response.<Map<String, Object>>builder().code(ResponseCode.UN_ERROR.getCode())
                    .info("任务不存在: " + taskId).build();
        }
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(Map.of("taskId", taskId, "topic", report.getTopic(),
                        "partial", report.isPartial()))
                .build();
    }

    /** 任务报告 */
    @GetMapping("/tasks/{taskId}/report")
    public Response<ResearchReportVO> report(@PathVariable String taskId) {
        ResearchReportVO report = reports.get(taskId);
        if (report == null) {
            return Response.<ResearchReportVO>builder().code(ResponseCode.UN_ERROR.getCode())
                    .info("报告不存在: " + taskId).build();
        }
        return Response.<ResearchReportVO>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(report)
                .build();
    }

    /** 报告导出（Markdown + 元数据 JSON） */
    @PostMapping("/tasks/{taskId}/export")
    public Response<List<String>> export(@PathVariable String taskId) {
        ResearchReportVO report = reports.get(taskId);
        if (report == null) {
            return Response.<List<String>>builder().code(ResponseCode.UN_ERROR.getCode())
                    .info("报告不存在: " + taskId).build();
        }
        return Response.<List<String>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(new ReportExporter().exportAll(report, 0, 0, 0))
                .build();
    }
}
