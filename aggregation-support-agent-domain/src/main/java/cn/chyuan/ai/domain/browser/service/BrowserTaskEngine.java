package cn.chyuan.ai.domain.browser.service;

import cn.chyuan.ai.domain.browser.adapter.port.BrowserDriverPort;
import cn.chyuan.ai.domain.browser.model.valobj.BrowserActionVO;
import cn.chyuan.ai.domain.browser.model.valobj.PageSnapshotVO;
import cn.chyuan.ai.domain.browser.model.valobj.TaskRunResultVO;

import java.util.ArrayList;
import java.util.List;

/**
 * 浏览器任务执行引擎（工单 0342 AQ4，browser-use agent loop 确定性内核）。
 * 动作序列逐步执行（经 BrowserDriverPort）+逐步检查点（校验器过闸+快照留痕）
 * +失败中止语义（失败步留动作/快照摘要/错误上下文）。domain 纯函数编排。
 */
public class BrowserTaskEngine {

    private final BrowserDriverPort driver;
    private final ActionValidator validator = new ActionValidator();

    public BrowserTaskEngine(BrowserDriverPort driver) {
        if (driver == null) {
            throw new IllegalArgumentException("驱动端口不能为空");
        }
        this.driver = driver;
    }

    public TaskRunResultVO run(String taskId, String startUrl,
                               List<BrowserActionVO> actions) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("任务ID不能为空");
        }
        List<TaskRunResultVO.StepRecordVO> steps = new ArrayList<>();
        PageSnapshotVO current = driver.initial(startUrl);
        for (int i = 0; i < actions.size(); i++) {
            BrowserActionVO action = actions.get(i);
            long start = System.nanoTime();
            List<cn.chyuan.ai.domain.browser.model.valobj.ActionErrorVO> errors = validator.validate(action);
            if (!errors.isEmpty()) {
                return failed(taskId, steps, i, action, current,
                        "动作校验失败: " + errors.get(0).getField() + " " + errors.get(0).getCode(), start);
            }
            try {
                current = driver.execute(action, current);
                steps.add(TaskRunResultVO.StepRecordVO.builder()
                        .index(i)
                        .actionSummary(summary(action))
                        .status("SUCCESS")
                        .costMs((System.nanoTime() - start) / 1_000_000)
                        .snapshotDigest(String.valueOf(current.getElements().size()))
                        .build());
            } catch (RuntimeException e) {
                return failed(taskId, steps, i, action, current, e.getMessage(), start);
            }
        }
        return TaskRunResultVO.builder()
                .taskId(taskId)
                .success(true)
                .steps(steps)
                .failedStep(-1)
                .build();
    }

    private TaskRunResultVO failed(String taskId, List<TaskRunResultVO.StepRecordVO> steps,
                                   int index, BrowserActionVO action, PageSnapshotVO snapshot,
                                   String error, long start) {
        steps.add(TaskRunResultVO.StepRecordVO.builder()
                .index(index)
                .actionSummary(summary(action))
                .status("FAILED")
                .costMs((System.nanoTime() - start) / 1_000_000)
                .build());
        String context = new SnapshotSummarizer(5).summarize(snapshot);
        return TaskRunResultVO.builder()
                .taskId(taskId)
                .success(false)
                .steps(steps)
                .failedStep(index)
                .failureContext("动作[" + summary(action) + "]失败：" + error + "；页面快照：" + context)
                .build();
    }

    private String summary(BrowserActionVO action) {
        return switch (action.getType()) {
            case BrowserActionVO.NAVIGATE -> "navigate " + action.getUrl();
            case BrowserActionVO.TYPE -> "type " + action.getSelector();
            case BrowserActionVO.EXTRACT -> "extract " + action.getFields().keySet();
            default -> action.getType() + " " + (action.getSelector() == null ? "" : action.getSelector());
        };
    }
}
