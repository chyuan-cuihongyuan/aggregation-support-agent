package cn.chyuan.ai.domain.browser.adapter.port;

import cn.chyuan.ai.domain.browser.model.valobj.TaskRunResultVO;

import java.util.List;

/**
 * 浏览器审计端口（AQ7：动作审计日志 + 截图存证占位，puppeteer 存证思想）。
 */
public interface BrowserAuditPort {

    /** 动作审计日志登记 */
    void log(AuditEntry entry);

    /** 截图存证（真实截图字节由 infrastructure 提供；占位实现存摘要） */
    void evidence(String taskId, int step, byte[] screenshot, String note);

    /** 按任务查审计（步序有序） */
    List<AuditEntry> byTask(String taskId);

    /** 按任务查存证清单 */
    List<String> evidenceList(String taskId);

    /** 审计条目 */
    record AuditEntry(String taskId, int step, String actionSummary,
                      String targetElement, String status, long costMs) {
    }
}
