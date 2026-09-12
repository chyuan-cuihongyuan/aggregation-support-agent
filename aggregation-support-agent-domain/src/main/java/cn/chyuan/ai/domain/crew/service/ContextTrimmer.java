package cn.chyuan.ai.domain.crew.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 交接上下文裁剪器（工单 0216 AC4）—
 * 按字符预算裁剪交接上下文：优先级 = ① 任务描述 ② 最近结论（时间倒序保留）
 * ③ 其余上下文行；超预算即截断并标注省略行数。纯函数。
 *
 * @author chyuan
 */
public final class ContextTrimmer {

    private ContextTrimmer() {
    }

    /** 裁剪结果：文本 + 是否截断 + 省略行数 */
    public record TrimmedContext(String text, boolean truncated, int omittedLines) {
    }

    /**
     * @param task         任务描述（最高优先级，超预算时尾部截断）
     * @param recentOutputs 最近结论（时间正序传入，预算内从最新往回保留）
     * @param extraLines    其余上下文行（最低优先级）
     * @param budgetChars   字符预算（≤0 视为不限制）
     */
    public static TrimmedContext trim(String task, List<String> recentOutputs,
            List<String> extraLines, int budgetChars) {
        String safeTask = task == null ? "" : task;
        List<String> outputs = recentOutputs == null ? List.of() : recentOutputs;
        List<String> extras = extraLines == null ? List.of() : extraLines;
        if (budgetChars <= 0) {
            return new TrimmedContext(join(safeTask, outputs, extras), false, 0);
        }
        int remaining = budgetChars;
        // ① 任务（超长尾部截断）
        String taskPart = safeTask;
        if (taskPart.length() > remaining) {
            return new TrimmedContext(taskPart.substring(0, Math.max(0, remaining)) + "…", true, 0);
        }
        remaining -= taskPart.length();
        // ② 最近结论（从最新往回保留）
        List<String> keptOutputs = new ArrayList<>();
        int omitted = 0;
        for (int i = outputs.size() - 1; i >= 0; i--) {
            String line = outputs.get(i);
            if (line.length() <= remaining) {
                keptOutputs.add(0, line);
                remaining -= line.length();
            } else {
                omitted += i + 1;
                break;
            }
        }
        // ③ 其余上下文行（正序，装得下就带）
        List<String> keptExtras = new ArrayList<>();
        for (String line : extras) {
            if (line.length() <= remaining) {
                keptExtras.add(line);
                remaining -= line.length();
            } else {
                omitted++;
            }
        }
        boolean truncated = omitted > 0;
        String text = join(taskPart, keptOutputs, keptExtras)
                + (truncated ? "\n[省略 " + omitted + " 行]" : "");
        return new TrimmedContext(text, truncated, omitted);
    }

    private static String join(String task, List<String> outputs, List<String> extras) {
        StringBuilder sb = new StringBuilder(task);
        for (String line : outputs) {
            sb.append('\n').append(line);
        }
        for (String line : extras) {
            sb.append('\n').append(line);
        }
        return sb.toString();
    }
}
