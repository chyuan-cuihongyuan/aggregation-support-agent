package cn.chyuan.ai.domain.crew.groupchat.service;

import cn.chyuan.ai.domain.crew.groupchat.model.GroupChatMessageVO;
import cn.chyuan.ai.domain.crew.groupchat.model.GroupChatReplayViewVO;
import cn.chyuan.ai.domain.crew.groupchat.model.GroupChatScorecardVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 群聊记分卡单测（工单 0322 AN8）：指标计算/均衡度边界/聚合/空会话。
 */
class GroupChatScorecardTest {

    private final GroupChatScorecard scorecard = new GroupChatScorecard();

    private GroupChatReplayViewVO view(String status, int maxRounds, int totalRounds,
                                       List<String> speakers, List<String> participants) {
        java.util.List<GroupChatMessageVO> messages = new java.util.ArrayList<>();
        for (int i = 0; i < speakers.size(); i++) {
            messages.add(GroupChatMessageVO.builder()
                    .round(1 + (i % Math.max(1, totalRounds)))
                    .speaker(speakers.get(i))
                    .content("s" + i)
                    .triggerStrategy("manual")
                    .tokenEstimate(2)
                    .build());
        }
        return GroupChatReplayViewVO.builder()
                .sessionId("s")
                .participants(participants)
                .maxRounds(maxRounds)
                .finalStatus(status)
                .replaySequence(messages)
                .totalRounds(totalRounds)
                .totalTokens(messages.size() * 2)
                .build();
    }

    @Test
    void 四类指标计算正确() {
        // 3 轮上限用了 2 轮，CONSENSUS，3 条发言 alice1/bob1/alice1
        GroupChatScorecardVO score = scorecard.score(
                view("CONSENSUS", 3, 2, List.of("alice", "bob", "alice"), List.of("alice", "bob")));
        assertEquals(2.0 / 3, score.getRoundEfficiency(), 1e-9);
        assertEquals(6, score.getTotalTokens());
        assertEquals(1.0, score.getCompletionScore(), 1e-9);
        assertEquals(1.0, score.getConsensusRate(), 1e-9);
        assertEquals(1, score.getSessionCount());
        // 均衡度：3 vs 1 次发言 → 介于独占与均衡之间
        assertTrue(score.getBalanceScore() > 0 && score.getBalanceScore() < 1.0);
    }

    @Test
    void 终态加权与发言均衡边界() {
        assertEquals(0.5, scorecard.score(view("TERMINATED", 2, 1,
                List.of("a", "b"), List.of("a", "b"))).getCompletionScore());
        assertEquals(0.0, scorecard.score(view("EXCEEDED", 2, 2,
                List.of("a", "b"), List.of("a", "b"))).getCompletionScore());
        // 完全均衡：发言均等 → 基尼 0 → 均衡度 1
        assertEquals(1.0, scorecard.score(view("CONSENSUS", 2, 1,
                List.of("a", "b"), List.of("a", "b"))).getBalanceScore(), 1e-9);
        // 独占：一人包揽全部发言 → 基尼 1 → 均衡度 0
        assertEquals(0.0, scorecard.score(view("CONSENSUS", 2, 1,
                List.of("a", "a", "a"), List.of("a", "b"))).getBalanceScore(), 1e-9);
    }

    @Test
    void 多会话聚合共识率() {
        GroupChatReplayViewVO consensus = view("CONSENSUS", 2, 1, List.of("a", "b"), List.of("a", "b"));
        GroupChatReplayViewVO terminated = view("TERMINATED", 2, 1, List.of("a", "b"), List.of("a", "b"));
        GroupChatScorecardVO aggregate = scorecard.aggregate(List.of(consensus, terminated));
        assertEquals(2, aggregate.getSessionCount());
        assertEquals(0.5, aggregate.getConsensusRate(), 1e-9);
        assertEquals(0.75, aggregate.getCompletionScore(), 1e-9);
        assertEquals(8, aggregate.getTotalTokens());
    }

    @Test
    void 空会话与非法输入() {
        GroupChatScorecardVO empty = scorecard.score(view("RUNNING", 2, 0,
                List.of(), List.of("a", "b")));
        assertEquals(0.0, empty.getRoundEfficiency());
        assertEquals(0.0, empty.getBalanceScore());
        assertThrows(IllegalArgumentException.class, () -> scorecard.score(null));
        assertThrows(IllegalArgumentException.class, () -> scorecard.aggregate(List.of()));
    }
}
