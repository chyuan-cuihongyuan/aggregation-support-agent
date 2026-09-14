package cn.chyuan.ai.domain.crew.groupchat.service;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 群聊状态机单测（工单 0315 AN1）：转移矩阵/轮次上限/注册与发言边界。
 */
class GroupChatSessionTest {

    private GroupChatSession started() {
        GroupChatSession session = new GroupChatSession(3);
        session.register("alice").register("bob");
        session.start();
        return session;
    }

    @Test
    void 合法启动与逐轮发言() {
        GroupChatSession session = started();
        assertEquals(GroupChatSession.Status.RUNNING, session.getStatus());
        session.recordSpeech("alice", "第一轮发言", "round-robin");
        session.nextRound();
        session.recordSpeech("bob", "第二轮发言", "manual");
        assertEquals(2, session.getMessages().size());
        assertEquals(2, session.getCurrentRound());
        session.finish(GroupChatSession.Status.CONSENSUS, "达成一致");
        assertEquals(GroupChatSession.Status.CONSENSUS, session.getStatus());
        assertEquals("达成一致", session.getEndReason());
    }

    @Test
    void 非法转移矩阵拒绝() {
        GroupChatSession idle = new GroupChatSession(2);
        // IDLE 直接终态拒绝
        assertThrows(IllegalStateException.class, () -> idle.finish(GroupChatSession.Status.TERMINATED, "x"));
        // IDLE 重复启动后再启动拒绝
        GroupChatSession session = started();
        assertThrows(IllegalStateException.class, session::start);
        // 终态后任何操作拒绝
        session.finish(GroupChatSession.Status.TERMINATED, "结束");
        assertThrows(IllegalStateException.class, () -> session.recordSpeech("alice", "迟到", "manual"));
        assertThrows(IllegalStateException.class, session::nextRound);
        // 非终态参数拒绝
        assertThrows(IllegalArgumentException.class, () -> session.finish(GroupChatSession.Status.RUNNING, "x"));
    }

    @Test
    void 注册与发言边界() {
        GroupChatSession session = new GroupChatSession(2);
        assertThrows(IllegalArgumentException.class, () -> session.register(" "));
        session.register("alice");
        assertThrows(IllegalArgumentException.class, () -> session.register("alice"), "重复注册拒绝");
        session.register("bob");
        session.start();
        assertThrows(IllegalStateException.class, () -> session.register("carol"), "启动后注册拒绝");
        assertThrows(IllegalArgumentException.class, () -> session.recordSpeech("ghost", "未注册发言", "manual"));
        assertTrue(session.getParticipants().contains("alice"));
        assertEquals(2, session.getParticipants().size());
    }

    @Test
    void 轮次上限与空轮推进拒绝() {
        GroupChatSession session = new GroupChatSession(2);
        session.register("a").register("b");
        session.start();
        // 当前轮（1）无发言不能推进
        assertThrows(IllegalStateException.class, session::nextRound);
        session.recordSpeech("a", "发言1", "manual");
        session.nextRound();
        session.recordSpeech("b", "发言2", "manual");
        // 2 轮已满再推进拒绝（应转 EXCEEDED）
        assertThrows(IllegalStateException.class, session::nextRound);
        session.finish(GroupChatSession.Status.EXCEEDED, "轮次超限");
        assertEquals(GroupChatSession.Status.EXCEEDED, session.getStatus());
        // 轨迹可审计：记录转移来源 [IDLE, RUNNING]
        assertEquals(2, session.statusTrail().size());
    }

    @Test
    void 发言留痕字段完整() {
        GroupChatSession session = started();
        session.recordSpeech("alice", "hello", "weighted");
        var message = session.getMessages().get(0);
        assertEquals(1, message.getRound());
        assertEquals("alice", message.getSpeaker());
        assertEquals("weighted", message.getTriggerStrategy());
        assertEquals("hello".length(), message.getTokenEstimate());
        assertFalse(session.getMessages().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> session.getMessages().add(null), "历史只读");
    }
}
