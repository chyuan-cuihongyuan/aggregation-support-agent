package cn.chyuan.ai.domain.browser.service;

import cn.chyuan.ai.domain.browser.adapter.port.BrowserDriverPort;
import cn.chyuan.ai.domain.browser.model.valobj.BrowserActionVO;
import cn.chyuan.ai.domain.browser.model.valobj.ElementVO;
import cn.chyuan.ai.domain.browser.model.valobj.LocateResult;
import cn.chyuan.ai.domain.browser.model.valobj.PageSnapshotVO;
import cn.chyuan.ai.domain.browser.model.valobj.RecoveryDecision;
import cn.chyuan.ai.domain.browser.model.valobj.TaskRunResultVO;

import java.util.ArrayList;
import java.util.List;

/**
 * 失败恢复策略（工单 0346 AQ8，browser-use 错误恢复思想）。
 * 三级恢复链：一级重试（同动作重放，幂等动作白名单）/二级跳过（skippable 标记）/
 * 三级替代定位（role+name 文本重定位后重放）；恢复点检查点续跑（最近成功步），
 * 全程恢复留痕。domain 纯函数编排（复用 AQ4 引擎语义）。
 */
public class RecoveryPolicy {

    /** 幂等动作白名单（可安全重试的动作类型） */
    private static final List<String> IDEMPOTENT = List.of(
            BrowserActionVO.NAVIGATE, BrowserActionVO.SCROLL, BrowserActionVO.EXTRACT, BrowserActionVO.WAIT);

    private final int maxRetries;
    private final List<String> trace = new ArrayList<>();

    public RecoveryPolicy(int maxRetries) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("重试次数不可为负");
        }
        this.maxRetries = maxRetries;
    }

    /** 恢复决策：错误类型 → 恢复级别 */
    public RecoveryDecision decide(BrowserActionVO action, String error) {
        if (error != null && error.contains("校验失败")) {
            return RecoveryDecision.of(RecoveryDecision.ABORT, "动作非法不可恢复");
        }
        if (IDEMPOTENT.contains(action.getType())) {
            return RecoveryDecision.of(RecoveryDecision.RETRY, "幂等动作可重试");
        }
        if (action.isSkippable()) {
            return RecoveryDecision.of(RecoveryDecision.SKIP, "动作标记可跳过");
        }
        return RecoveryDecision.of(RecoveryDecision.RELOCATE, "非幂等动作尝试替代定位");
    }

    /** 带恢复的任务执行：失败时按决策逐级恢复，全部失败返回失败结果（带恢复留痕） */
    public TaskRunResultVO runWithRecovery(String taskId, String startUrl,
                                           List<BrowserActionVO> actions,
                                           BrowserDriverPort driver,
                                           ElementLocator locator) {
        BrowserTaskEngine engine = new BrowserTaskEngine(driver);
        TaskRunResultVO result = engine.run(taskId, startUrl, actions);
        int guard = 0;
        while (!result.isSuccess() && guard++ < 10) {
            int failedStep = result.getFailedStep();
            BrowserActionVO action = actions.get(failedStep);
            RecoveryDecision decision = decide(action, result.getFailureContext());
            trace.add("step" + failedStep + ":" + decision.getLevel() + ":" + decision.getReason());
            switch (decision.getLevel()) {
                case RecoveryDecision.RETRY -> {
                    // 只重放失败步（一级）
                    TaskRunResultVO retry = engine.run(taskId + ":retry", startUrl,
                            List.of(action));
                    if (retry.isSuccess()) {
                        trace.add("step" + failedStep + ":重试成功");
                        return trimmedSuccess(taskId, result, retry, failedStep);
                    }
                    if (maxRetries == 0) {
                        return failWithTrace(taskId, result);
                    }
                }
                case RecoveryDecision.SKIP -> {
                    trace.add("step" + failedStep + ":跳过继续");
                    List<BrowserActionVO> rest = actions.subList(failedStep + 1, actions.size());
                    TaskRunResultVO continued = engine.run(taskId + ":skip", startUrl, rest);
                    if (continued.isSuccess()) {
                        trace.add("step" + failedStep + ":跳后续跑成功");
                        return trimmedSuccess(taskId, result, continued, failedStep);
                    }
                    return failWithTrace(taskId, result);
                }
                case RecoveryDecision.RELOCATE -> {
                    // 三级：用文本重定位重放（定位器命中唯一则重试一次）
                    PageSnapshotVO snapshot = driver.initial(startUrl);
                    LocateResult located = locator.locate(snapshot, action.getSelector(),
                            action.getSelector(), null, null, null);
                    if (located.getStatus().equals("HIT")) {
                        ElementVO element = located.getElement();
                        BrowserActionVO relocated = BrowserActionVO.builder()
                                .type(action.getType()).selector(element.getSelector())
                                .text(action.getText()).amount(action.getAmount())
                                .direction(action.getDirection()).fields(action.getFields())
                                .waitMs(action.getWaitMs()).build();
                        TaskRunResultVO retried = engine.run(taskId + ":relocate", startUrl,
                                List.of(relocated));
                        if (retried.isSuccess()) {
                            trace.add("step" + failedStep + ":替代定位成功");
                            return trimmedSuccess(taskId, result, retried, failedStep);
                        }
                    }
                    return failWithTrace(taskId, result);
                }
                default -> {
                    return failWithTrace(taskId, result);
                }
            }
        }
        return result.isSuccess() ? result : failWithTrace(taskId, result);
    }

    /** 恢复留痕（外部读取） */
    public List<String> trace() {
        return List.copyOf(trace);
    }

    private TaskRunResultVO trimmedSuccess(String taskId, TaskRunResultVO original,
                                           TaskRunResultVO recovery, int failedStep) {
        List<TaskRunResultVO.StepRecordVO> steps = new ArrayList<>(
                original.getSteps().subList(0, failedStep));
        steps.addAll(recovery.getSteps());
        return TaskRunResultVO.builder()
                .taskId(taskId)
                .success(true)
                .steps(steps)
                .failedStep(-1)
                .build();
    }

    private TaskRunResultVO failWithTrace(String taskId, TaskRunResultVO original) {
        return TaskRunResultVO.builder()
                .taskId(taskId)
                .success(false)
                .steps(original.getSteps())
                .failedStep(original.getFailedStep())
                .failureContext(original.getFailureContext()
                        + "；恢复轨迹：" + String.join(" | ", trace))
                .build();
    }
}
