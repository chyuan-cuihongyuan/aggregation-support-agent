package cn.chyuan.ai.domain.agent.service.chat;

import cn.chyuan.ai.domain.agent.model.entity.ChatHistoryEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话重建回灌规划器 —— 借鉴 spring-ai-mount-buzhou memory 的「预算化压缩」思想
 * 在本仓基线的最小落地：把「回灌 6 条 + 单条截断 2000 字符」的两点式限制，
 * 升级为「条数上限 + 单条上限 + 总预算」三级预算，且分配策略改为<strong>最新优先</strong>。
 *
 * <p>与旧逻辑的差异（都是行为增强，非破坏）：</p>
 * <ul>
 *   <li><b>总预算</b>：{@code aiops.memory.replay-budget-chars}（默认 8000）约束整批回灌体积——
 *       旧逻辑 6 条 × 2000 字符理论上可注入 ~12K 字符，预算制保证上界；</li>
 *   <li><b>最新优先</b>：预算不足时从<strong>最新</strong>记录开始保留（贴近当前对话的上下文价值最高），
 *       旧逻辑固定取前 N 条（queryBySessionId 的返回序）；</li>
 *   <li><b>首尾保留截断</b>：超长回复保留头 60% + 尾 40%（结论通常在尾部），中段以显式标记省略——
 *       旧逻辑硬截前 2000 字符会丢失结论。</li>
 * </ul>
 *
 * <p>与 buzhou 原实现的偏差：buzhou 按 token 分级（滚动摘要→要点化→拒识），本实现按字符预算
 * 单级规划；LLM 摘要式压缩需要额外模型调用，留给后续演进。</p>
 */
@Slf4j
@Component
public class HistoryReplayPlanner {

    private static final String HEAD_TAIL_MARK = "\n...[历史回复中段省略 %d 字符]...\n";

    @Value("${aiops.memory.max-replay:6}")
    private int maxReplay;

    @Value("${aiops.memory.per-answer-chars:2000}")
    private int perAnswerChars;

    @Value("${aiops.memory.replay-budget-chars:8000}")
    private int replayBudgetChars;

    /**
     * 单条回灌消息。
     */
    public record ReplayItem(String author, String text) {
    }

    /**
     * 回灌计划：items 已按时间正序排列，可直接依序追加。
     */
    public record ReplayPlan(List<ReplayItem> items, int totalRecords, int plannedRecords,
                             long plannedChars, boolean budgetCapped) {
    }

    /**
     * 规划回灌批次：取最近 maxReplay 条记录，按最新优先分配总预算，输出时间正序。
     */
    public ReplayPlan plan(List<ChatHistoryEntity> histories) {
        if (histories == null || histories.isEmpty()) {
            return new ReplayPlan(List.of(), 0, 0, 0, false);
        }

        List<ChatHistoryEntity> candidates =
                histories.size() > maxReplay ? histories.subList(histories.size() - maxReplay, histories.size()) : histories;

        // 最新优先分配预算；按「对」收集，最后对级反转回时间正序（对内 user→model 顺序保持）
        List<List<ReplayItem>> pairsNewestFirst = new ArrayList<>();
        long used = 0;
        boolean budgetCapped = false;
        for (int i = candidates.size() - 1; i >= 0; i--) {
            ChatHistoryEntity history = candidates.get(i);
            String question = history.getQuestion();
            String answer = history.getAnswer();

            List<ReplayItem> pair = new ArrayList<>(2);
            long pairCost = 0;
            if (question != null && !question.isBlank()) {
                pair.add(new ReplayItem("user", question));
                pairCost += question.length();
            }
            if (answer != null && !answer.isBlank()) {
                String bounded = boundAnswer(answer);
                pair.add(new ReplayItem("model", bounded));
                pairCost += bounded.length();
            }
            if (pair.isEmpty()) {
                continue; // 空记录跳过，不占预算
            }
            if (used + pairCost > replayBudgetChars && !pairsNewestFirst.isEmpty()) {
                // 预算不足且已有至少一条记录——丢弃更早的这对，保上界
                budgetCapped = true;
                break;
            }
            pairsNewestFirst.add(pair);
            used += pairCost;
        }

        List<ReplayItem> chronological = new ArrayList<>();
        for (int i = pairsNewestFirst.size() - 1; i >= 0; i--) {
            chronological.addAll(pairsNewestFirst.get(i));
        }

        if (budgetCapped) {
            log.info("回灌预算截断: totalRecords={}, plannedRecords={}, usedChars={}, budgetChars={}",
                    histories.size(), pairsNewestFirst.size(), used, replayBudgetChars);
        }
        return new ReplayPlan(chronological, histories.size(), pairsNewestFirst.size(), used, budgetCapped);
    }

    /**
     * 单条回复上限：超长时保头 60% + 尾 40%，中段以显式标记省略（结论在尾部，不能丢）。
     */
    String boundAnswer(String answer) {
        if (answer.length() <= perAnswerChars) {
            return answer;
        }
        int keep = perAnswerChars - 64; // 预留标记空间
        int headLen = (int) (keep * 0.6);
        int tailLen = keep - headLen;
        int omitted = answer.length() - headLen - tailLen;
        return answer.substring(0, headLen)
                + String.format(HEAD_TAIL_MARK, omitted)
                + answer.substring(answer.length() - tailLen);
    }
}
