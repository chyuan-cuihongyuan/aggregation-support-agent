package cn.chyuan.ai.test.domain.chat;

import cn.chyuan.ai.domain.agent.model.entity.ChatHistoryEntity;
import cn.chyuan.ai.domain.agent.service.chat.HistoryReplayPlanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回灌规划器行为锁（SELFLOOP loop-10 / C02）：
 * 三级预算（条数/单条/总量）、最新优先分配、时间正序输出、首尾保留截断。
 */
class HistoryReplayPlannerTest {

    private HistoryReplayPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new HistoryReplayPlanner();
        ReflectionTestUtils.setField(planner, "maxReplay", 6);
        ReflectionTestUtils.setField(planner, "perAnswerChars", 200);
        ReflectionTestUtils.setField(planner, "replayBudgetChars", 1000);
    }

    @Test
    void underAllLimitsReplaysEverythingInChronologicalOrder() {
        HistoryReplayPlanner.ReplayPlan plan = planner.plan(List.of(
                record("q1", "a1"), record("q2", "a2"), record("q3", "a3")));

        assertThat(plan.items()).hasSize(6); // 3 问 + 3 答
        assertThat(plan.items().get(0).author()).isEqualTo("user");
        assertThat(plan.items().get(0).text()).isEqualTo("q1");
        assertThat(plan.items().get(5).text()).isEqualTo("a3");
        assertThat(plan.budgetCapped()).isFalse();
        assertThat(plan.plannedRecords()).isEqualTo(3);
    }

    @Test
    void longAnswerKeepsHeadAndTailWithExplicitMark() {
        String longAnswer = "H".repeat(300); // 300 > 200 上限
        HistoryReplayPlanner.ReplayPlan plan = planner.plan(List.of(record("q", longAnswer)));

        String bounded = plan.items().get(1).text();
        assertThat(bounded).contains("[历史回复中段省略");
        assertThat(bounded.length()).isLessThanOrEqualTo(200 + 64);
        // 首尾内容都在：头 60% 的 H 与尾部 H 首尾相接验证——构造首尾可区分的串再验一次
        String marked = "A".repeat(120) + "M".repeat(120) + "Z".repeat(120);
        String boundedMarked = planner.plan(List.of(record("q", marked))).items().get(1).text();
        assertThat(boundedMarked).startsWith("A");
        assertThat(boundedMarked).endsWith("Z");
        assertThat(boundedMarked).doesNotContain("M"); // 中段被省略
    }

    @Test
    void budgetCappedKeepsNewestPairAndFlags() {
        // 收紧预算到 400：每对约 157 字符（250 字符答案先经单条截断），只能装下最新 2 对
        ReflectionTestUtils.setField(planner, "replayBudgetChars", 400);
        List<ChatHistoryEntity> histories = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            histories.add(record("q" + i, "y".repeat(250)));
        }

        HistoryReplayPlanner.ReplayPlan plan = planner.plan(histories);

        assertThat(plan.budgetCapped()).isTrue();
        assertThat(plan.plannedRecords()).isEqualTo(2);
        // 时间正序：保留的最近两对是 q4、q5
        assertThat(plan.items().get(0).text()).isEqualTo("q4");
        assertThat(plan.items().get(2).text()).isEqualTo("q5");
    }

    @Test
    void countCapTakesMostRecentRecords() {
        List<ChatHistoryEntity> histories = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            histories.add(record("q" + i, "a" + i));
        }

        HistoryReplayPlanner.ReplayPlan plan = planner.plan(histories);

        assertThat(plan.plannedRecords()).isEqualTo(6);
        assertThat(plan.items().get(0).text()).isEqualTo("q4"); // 第 5 条（10 条中的最近 6 条从 q4 起）
        assertThat(plan.items().get(11).text()).isEqualTo("a9");
    }

    @Test
    void nullAndEmptySafe() {
        assertThat(planner.plan(null).items()).isEmpty();
        assertThat(planner.plan(List.of()).items()).isEmpty();
        // 全空记录跳过不占预算
        HistoryReplayPlanner.ReplayPlan plan = planner.plan(List.of(record(null, null)));
        assertThat(plan.items()).isEmpty();
        assertThat(plan.plannedRecords()).isEqualTo(0);
    }

    private ChatHistoryEntity record(String question, String answer) {
        ChatHistoryEntity entity = new ChatHistoryEntity();
        entity.setQuestion(question);
        entity.setAnswer(answer);
        return entity;
    }
}
