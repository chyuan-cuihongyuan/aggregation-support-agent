package cn.chyuan.ai.domain.crew.service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 多智能体运行录制器（工单 0219 AC7，借鉴 AutoGen 录制回放）—
 * 步骤序列（时间 + 角色 + 动作 + 摘要）线程安全追加；ReplayRenderer 输出
 * markdown 时间线，回放不执行真实 LLM。
 *
 * @author chyuan
 */
public class CrewRunRecorder {

    /** 单步骤记录 */
    public record CrewStep(long seq, long atMs, String role, String action, String summary) {
    }

    private final String runId;
    private final List<CrewStep> steps = new CopyOnWriteArrayList<>();
    private final long startedAt = System.currentTimeMillis();

    public CrewRunRecorder(String runId) {
        this.runId = runId == null || runId.isBlank() ? "crew-run" : runId;
    }

    /** 追加一步（seq 自增） */
    public void record(String role, String action, String summary) {
        steps.add(new CrewStep(steps.size() + 1, System.currentTimeMillis(), role,
                action == null ? "" : action, summary == null ? "" : summary));
    }

    public String runId() {
        return runId;
    }

    public List<CrewStep> steps() {
        return List.copyOf(steps);
    }

    public int size() {
        return steps.size();
    }

    /** 回放渲染为 markdown 时间线（纯文本渲染，零 LLM 调用） */
    public static String renderMarkdown(String runId, List<CrewStep> steps) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 多智能体运行回放\n\n");
        sb.append("- 运行 id：").append(runId).append('\n');
        sb.append("- 步骤数：").append(steps.size()).append("\n\n");
        sb.append("| # | 时间 | 角色 | 动作 | 摘要 |\n");
        sb.append("|---|------|------|------|------|\n");
        long first = steps.isEmpty() ? 0 : steps.get(0).atMs();
        for (CrewStep step : steps) {
            sb.append("| ").append(step.seq())
                    .append(" | +").append(step.atMs() - first).append("ms")
                    .append(" | ").append(step.role())
                    .append(" | ").append(step.action())
                    .append(" | ").append(step.summary().replace("\n", " "))
                    .append(" |\n");
        }
        return sb.toString();
    }
}
