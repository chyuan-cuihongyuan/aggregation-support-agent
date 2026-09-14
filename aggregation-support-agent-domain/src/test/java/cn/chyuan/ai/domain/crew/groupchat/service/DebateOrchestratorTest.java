package cn.chyuan.ai.domain.crew.groupchat.service;

import cn.chyuan.ai.domain.crew.groupchat.model.DebateResultVO;
import cn.chyuan.ai.domain.crew.groupchat.model.DebateSpeechVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 辩论编排器单测（工单 0319 AN5）：轮转顺序/裁决两路径/占位与边界。
 */
class DebateOrchestratorTest {

    @Test
    void 固定轮转发言顺序正确() {
        List<String> callOrder = new java.util.ArrayList<>();
        DebateOrchestrator orchestrator = new DebateOrchestrator(
                (side, topic, round, opponentPoints) -> {
                    callOrder.add(side + "#" + round);
                    return side + " 第" + round + "轮论点";
                },
                null);
        DebateResultVO result = orchestrator.debate("远程办公利弊", 2);
        // PRO→CON→JUDGE × 2 轮
        assertEquals(List.of("PRO#1", "CON#1", "JUDGE#1", "PRO#2", "CON#2", "JUDGE#2"), callOrder);
        assertEquals(6, result.getTranscript().size());
        assertEquals(1, result.getTranscript().get(0).getRound());
        assertEquals("CON", result.getTranscript().get(1).getSide());
        assertTrue(!result.isJudgedByPort(), "无裁决端口走模板兜底");
        assertEquals("DRAW", result.getWinner(), "正反轮数相同判平局");
    }

    @Test
    void 裁决端口正常路径留痕() {
        DebateOrchestrator orchestrator = new DebateOrchestrator(
                (side, topic, round, opponentPoints) -> side + "论点",
                (topic, transcript) -> "判 PRO 胜：论据更充分");
        DebateResultVO result = orchestrator.debate("议题A", 1);
        assertTrue(result.isJudgedByPort());
        assertEquals("判 PRO 胜：论据更充分", result.getVerdict());
        assertEquals("PRO", result.getWinner());
    }

    @Test
    void 裁决异常与空值走模板兜底() {
        DebateOrchestrator broken = new DebateOrchestrator(
                (side, topic, round, opponentPoints) -> side + "论点",
                (topic, transcript) -> {
                    throw new IllegalStateException("裁判模型挂");
                });
        DebateResultVO result = broken.debate("议题B", 2);
        assertFalse(result.isJudgedByPort());
        assertTrue(result.getVerdict().contains("按轮次论点计数"));
        // 裁决文本胜方提取
        assertEquals("DRAW", DebateOrchestrator.winnerFrom("双方平局"));
        assertEquals("PRO", DebateOrchestrator.winnerFrom("PRO 更优"));
        assertEquals("CON", DebateOrchestrator.winnerFrom("CON 占优"));
        // 发言端口异常 → 占位继续
        DebateOrchestrator speechFail = new DebateOrchestrator(
                (side, topic, round, opponentPoints) -> {
                    throw new IllegalStateException("发言模型挂");
                }, null);
        DebateResultVO fallback = speechFail.debate("议题C", 1);
        assertTrue(fallback.getTranscript().get(0).getContent().contains("占位继续"));
    }

    @Test
    void 对抗上下文携带对方要点() {
        List<List<String>> seenOpponent = new java.util.ArrayList<>();
        DebateOrchestrator orchestrator = new DebateOrchestrator(
                (side, topic, round, opponentPoints) -> {
                    if ("CON".equals(side)) {
                        seenOpponent.add(List.copyOf(opponentPoints));
                    }
                    return side + "论点";
                }, null);
        orchestrator.debate("议题D", 1);
        // 反方发言时携带正方第 1 轮论点
        assertEquals(1, seenOpponent.size());
        assertEquals("PRO论点", seenOpponent.get(0).get(0));
        assertThrows(IllegalArgumentException.class, () -> orchestrator.debate("x", 0));
        assertThrows(IllegalArgumentException.class, () -> orchestrator.debate(" ", 1));
    }
}
