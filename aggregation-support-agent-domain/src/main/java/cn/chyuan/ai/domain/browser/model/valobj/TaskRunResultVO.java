package cn.chyuan.ai.domain.browser.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 浏览器任务运行结果值对象（AQ4：步级记录 + 终态）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TaskRunResultVO {

    /** 任务ID */
    private String taskId;

    /** 是否成功完成全部动作 */
    private boolean success;

    /** 步级记录（动作摘要/结果/耗时） */
    private List<StepRecordVO> steps;

    /** 失败步序号（从 0 起，成功为 -1） */
    private int failedStep;

    /** 失败上下文（动作摘要/快照摘要/错误） */
    private String failureContext;

    /** 步记录值对象 */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class StepRecordVO {
        private int index;
        private String actionSummary;
        private String status;
        private long costMs;
        private String snapshotDigest;
    }
}
