package cn.chyuan.ai.domain.crew.groupchat.service;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发言者选择策略单测（工单 0316 AN2）：轮转/权重/自动兜底。
 */
class SpeakerSelectorTest {

    private GroupChatSession session() {
        GroupChatSession session = new GroupChatSession(10);
        session.register("carol").register("alice").register("bob");
        session.start();
        return session;
    }

    @Test
    void 轮转按序循环() {
        SpeakerSelector.RoundRobin selector = new SpeakerSelector.RoundRobin();
        GroupChatSession session = session();
        // 注册序排序后 alice→bob→carol→alice 循环
        assertEquals("alice", selector.nextSpeaker(session));
        session.recordSpeech("alice", "1", selector.strategyName());
        assertEquals("bob", selector.nextSpeaker(session));
        session.recordSpeech("bob", "2", selector.strategyName());
        assertEquals("carol", selector.nextSpeaker(session));
        session.recordSpeech("carol", "3", selector.strategyName());
        assertEquals("alice", selector.nextSpeaker(session));
        assertEquals("round-robin", selector.strategyName());
    }

    @Test
    void 权重零权不选中且确定分布() {
        // 固定种子随机源：结果可复现
        RandomGenerator random = new java.util.SplittableRandom(42);
        SpeakerSelector.Weighted selector = new SpeakerSelector.Weighted(
                Map.of("alice", 0.0, "bob", 2.0, "carol", 1.0), random);
        GroupChatSession session = session();
        // 多次选择中 alice（零权）永不出现
        for (int i = 0; i < 50; i++) {
            String picked = selector.nextSpeaker(session);
            assertTrue("bob".equals(picked) || "carol".equals(picked), "零权角色不应被选中: " + picked);
            session.recordSpeech(picked, "发言" + i, selector.strategyName());
        }
        // 权重大者出现频率更高（bob 2 vs carol 1，50 次中 bob 明显多）
        long bob = session.getMessages().stream().filter(m -> m.getSpeaker().equals("bob")).count();
        long carol = session.getMessages().stream().filter(m -> m.getSpeaker().equals("carol")).count();
        assertTrue(bob > carol, "权重高者频率应更高 bob=" + bob + " carol=" + carol);
        // 确定性：同种子重放同序列
        SpeakerSelector.Weighted replay = new SpeakerSelector.Weighted(
                Map.of("alice", 0.0, "bob", 2.0, "carol", 1.0),
                new java.util.SplittableRandom(42));
        GroupChatSession fresh = session();
        for (int i = 0; i < 50; i++) {
            assertEquals(replay.nextSpeaker(fresh), session.getMessages().get(i).getSpeaker());
        }
        // 全零权无可选
        SpeakerSelector.Weighted allZero = new SpeakerSelector.Weighted(
                Map.of("alice", 0.0, "bob", 0.0, "carol", 0.0), random);
        assertNull(allZero.nextSpeaker(session()));
        assertThrows(IllegalArgumentException.class, () -> new SpeakerSelector.Weighted(Map.of(), random));
    }

    @Test
    void 自动选人端口异常兜底轮转() {
        GroupChatSession session = session();
        // 端口正常返回注册成员 → 采纳
        SpeakerSelector.Auto ok = new SpeakerSelector.Auto(s -> "bob");
        assertEquals("bob", ok.nextSpeaker(session));
        assertEquals("auto", ok.strategyName());
        // 端口返回未注册成员 → 兜底轮转
        SpeakerSelector.Auto ghost = new SpeakerSelector.Auto(s -> "ghost");
        assertEquals("alice", ghost.nextSpeaker(session));
        // 端口异常 → 兜底轮转
        SpeakerSelector.Auto broken = new SpeakerSelector.Auto(s -> {
            throw new IllegalStateException("LLM 挂");
        });
        assertEquals("alice", broken.nextSpeaker(session));
        // 端口返回 null → 兜底
        SpeakerSelector.Auto silent = new SpeakerSelector.Auto(s -> null);
        assertEquals("alice", silent.nextSpeaker(session));
    }
}
