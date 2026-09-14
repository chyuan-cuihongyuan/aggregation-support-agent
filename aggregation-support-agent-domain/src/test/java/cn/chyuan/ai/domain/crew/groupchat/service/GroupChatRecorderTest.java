package cn.chyuan.ai.domain.crew.groupchat.service;

import cn.chyuan.ai.domain.crew.groupchat.model.GroupChatReplayViewVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 群聊回放审计单测（工单 0321 AN7）：留痕完整/重放一致/中断会话/边界。
 */
class GroupChatRecorderTest {

    private final GroupChatRecorder recorder = new GroupChatRecorder();

    private GroupChatSession session(boolean finish) {
        GroupChatSession session = new GroupChatSession(3);
        session.register("alice").register("bob");
        session.start();
        session.recordSpeech("alice", "首轮观点", "round-robin");
        session.recordSpeech("bob", "回应观点", "auto");
        session.nextRound();
        session.recordSpeech("alice", "第二轮补充", "weighted");
        if (finish) {
            session.finish(GroupChatSession.Status.CONSENSUS, "达成一致");
        }
        return session;
    }

    @Test
    void 留痕字段完整且轮次有序() {
        GroupChatReplayViewVO view = recorder.record("gc-001", session(true));
        assertEquals("gc-001", view.getSessionId());
        assertEquals(2, view.getParticipants().size());
        assertEquals(3, view.getMaxRounds());
        assertEquals("CONSENSUS", view.getFinalStatus());
        assertEquals("达成一致", view.getEndReason());
        assertEquals(3, view.getReplaySequence().size());
        assertEquals(2, view.getTotalRounds(), "最后发言在第 2 轮");
        assertEquals("首轮观点".length() + "回应观点".length() + "第二轮补充".length(),
                view.getTotalTokens());
        // 轮次非降序
        for (int i = 1; i < view.getReplaySequence().size(); i++) {
            assertTrue(view.getReplaySequence().get(i).getRound()
                    >= view.getReplaySequence().get(i - 1).getRound());
        }
    }

    @Test
    void 重放序列与原始会话一致() {
        GroupChatSession session = session(true);
        GroupChatReplayViewVO view = recorder.record("gc-002", session);
        assertTrue(recorder.replayMatches(session, view));
        // 篡改回放序列 → 不一致
        GroupChatReplayViewVO tampered = recorder.record("gc-003", session);
        tampered.getReplaySequence().set(0, null);
        assertFalse(recorder.replayMatches(session, tampered));
    }

    @Test
    void 中途终止会话与空会话可回放() {
        GroupChatSession terminated = new GroupChatSession(2);
        terminated.register("a").register("b");
        terminated.start();
        terminated.recordSpeech("a", "唯一发言", "manual");
        terminated.finish(GroupChatSession.Status.TERMINATED, "外部终止");
        GroupChatReplayViewVO view = recorder.record("gc-004", terminated);
        assertEquals("TERMINATED", view.getFinalStatus());
        assertEquals(1, view.getReplaySequence().size());
        GroupChatReplayViewVO empty = recorder.record("gc-005", new GroupChatSession(1));
        assertEquals(0, empty.getReplaySequence().size());
        assertEquals(0, empty.getTotalRounds());
        assertEquals(0, empty.getTotalTokens());
        assertThrows(IllegalArgumentException.class, () -> recorder.record(" ", terminated));
    }
}
