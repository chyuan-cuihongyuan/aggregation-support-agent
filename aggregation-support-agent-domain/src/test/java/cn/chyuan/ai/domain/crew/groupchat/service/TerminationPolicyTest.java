package cn.chyuan.ai.domain.crew.groupchat.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 群聊终止条件单测（工单 0317 AN3）：四类条件/组合留痕/整词边界。
 */
class TerminationPolicyTest {

    private GroupChatSession sessionWith(String... speeches) {
        GroupChatSession session = new GroupChatSession(2);
        session.register("alice").register("bob");
        session.start();
        String[] speakers = {"alice", "bob"};
        for (int i = 0; i < speeches.length; i++) {
            session.recordSpeech(speakers[i % 2], speeches[i], "manual");
            if (i % 2 == 1 && i < speeches.length - 1) {
                session.nextRound();
            }
        }
        return session;
    }

    @Test
    void 终止令牌整词命中() {
        TerminationPolicy policy = new TerminationPolicy("[DONE]", "[同意]");
        GroupChatSession hit = sessionWith("讨论中", "结论如下 [DONE]");
        assertTrue(policy.evaluate(hit).shouldTerminate());
        assertTrue(policy.evaluate(hit).reasons().contains(TerminationPolicy.Reason.TOKEN));
        // 子串不算整词：DONE 嵌在 COMPLETEDONE 词内（无分词边界）不命中
        TerminationPolicy strict = new TerminationPolicy("DONE", "[同意]");
        GroupChatSession substring = sessionWith("COMPLETEDONE 词内嵌令牌不算独立词");
        // 整词分词后 COMPLETEDONE 是一个词，DONE 不是独立词
        assertFalse(strict.evaluate(substring).reasons().contains(TerminationPolicy.Reason.TOKEN));
        // 独立词命中
        GroupChatSession whole = sessionWith("任务 DONE");
        assertTrue(strict.evaluate(whole).reasons().contains(TerminationPolicy.Reason.TOKEN));
    }

    @Test
    void 轮次上限与共识判定() {
        TerminationPolicy policy = new TerminationPolicy("[DONE]", "[同意]");
        // 轮次上限：2 轮各有发言
        GroupChatSession full = sessionWith("一", "二", "三", "四");
        assertTrue(policy.evaluate(full).reasons().contains(TerminationPolicy.Reason.ROUND_LIMIT));
        // 共识未达成（bob 无同意令牌）
        assertFalse(policy.evaluate(full).reasons().contains(TerminationPolicy.Reason.CONSENSUS));
        // 共识达成：双方最近发言都含 [同意]
        GroupChatSession agreed = sessionWith("[同意]", "[同意]");
        assertTrue(policy.evaluate(agreed).reasons().contains(TerminationPolicy.Reason.CONSENSUS));
        // 单方同意不算共识
        GroupChatSession half = sessionWith("[同意]", "再想想");
        assertFalse(policy.evaluate(half).reasons().contains(TerminationPolicy.Reason.CONSENSUS));
    }

    @Test
    void 外部中断与多原因全量留痕() {
        TerminationPolicy policy = new TerminationPolicy("[DONE]", "[同意]");
        policy.requestExternalInterrupt();
        // alice 首轮说 DONE（令牌），双方末次发言都含同意令牌（共识）
        GroupChatSession session = new GroupChatSession(5);
        session.register("alice").register("bob");
        session.start();
        session.recordSpeech("alice", "结论 [DONE]", "manual");
        session.recordSpeech("bob", "[同意]", "manual");
        session.recordSpeech("alice", "确认 [同意]", "manual");
        TerminationPolicy.Result result = policy.evaluate(session);
        assertTrue(result.shouldTerminate());
        // 令牌 + 共识 + 外部三原因全量留痕（轮次上限未到不计入）
        assertTrue(result.reasons().contains(TerminationPolicy.Reason.TOKEN));
        assertTrue(result.reasons().contains(TerminationPolicy.Reason.CONSENSUS));
        assertTrue(result.reasons().contains(TerminationPolicy.Reason.EXTERNAL));
        assertEquals(3, result.reasons().size());
    }

    @Test
    void 无条件不终止与空会话() {
        TerminationPolicy policy = new TerminationPolicy("[DONE]", "[同意]");
        GroupChatSession open = sessionWith("普通发言", "继续讨论");
        TerminationPolicy.Result result = policy.evaluate(open);
        assertFalse(result.shouldTerminate());
        assertTrue(result.reasons().isEmpty());
    }
}
