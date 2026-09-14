package cn.chyuan.ai.domain.crew.groupchat.service;

import cn.chyuan.ai.domain.crew.groupchat.model.DebateResultVO;
import cn.chyuan.ai.domain.crew.groupchat.model.DebateSpeechVO;

import java.util.ArrayList;
import java.util.List;

/**
 * 辩论编排器（工单 0319 AN5，debate 思想）。
 * 正/反/裁判固定轮转发言（PRO→CON→JUDGE 点评 × N 轮）→ 终审裁决端口
 * （异常/空走模板兜底：按正反论点轮次计数判胜，平局 DRAW）。
 * 发言端口异常以占位发言继续留痕。domain 纯函数编排。
 */
public class DebateOrchestrator {

    /** 发言端口：角色立场 + 议题 + 轮次 + 对方要点 → 发言内容 */
    public interface SpeechPort {
        String speak(String side, String topic, int round, List<String> opponentPoints);
    }

    /** 裁决端口：议题 + 全部发言 → 裁决文本 */
    public interface JudgementPort {
        String judge(String topic, List<DebateSpeechVO> transcript);
    }

    private final SpeechPort speechPort;
    private final JudgementPort judgementPort;

    public DebateOrchestrator(SpeechPort speechPort, JudgementPort judgementPort) {
        this.speechPort = speechPort;
        this.judgementPort = judgementPort;
    }

    public DebateResultVO debate(String topic, int rounds) {
        if (rounds <= 0) {
            throw new IllegalArgumentException("轮数必须为正数");
        }
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("议题不能为空");
        }
        List<DebateSpeechVO> transcript = new ArrayList<>();
        for (int round = 1; round <= rounds; round++) {
            final int currentRound = round;
            transcript.add(DebateSpeechVO.builder()
                    .round(round).side("PRO")
                    .content(safeSpeak("PRO", topic, round, lastConPoints(transcript)))
                    .build());
            transcript.add(DebateSpeechVO.builder()
                    .round(round).side("CON")
                    .content(safeSpeak("CON", topic, round, lastProPoints(transcript)))
                    .build());
            transcript.add(DebateSpeechVO.builder()
                    .round(round).side("JUDGE")
                    .content(safeSpeak("JUDGE", topic, round,
                            transcript.stream().filter(s -> s.getRound() == currentRound)
                                    .map(DebateSpeechVO::getContent).toList()))
                    .build());
        }
        return judge(topic, rounds, transcript);
    }

    private DebateResultVO judge(String topic, int rounds, List<DebateSpeechVO> transcript) {
        if (judgementPort != null) {
            try {
                String verdict = judgementPort.judge(topic, transcript);
                if (verdict != null && !verdict.isBlank()) {
                    return DebateResultVO.builder()
                            .topic(topic).rounds(rounds).transcript(transcript)
                            .verdict(verdict).winner(winnerFrom(verdict)).judgedByPort(true)
                            .build();
                }
            } catch (RuntimeException ignored) {
                // 走模板兜底
            }
        }
        long proRounds = transcript.stream().filter(s -> "PRO".equals(s.getSide())).count();
        long conRounds = transcript.stream().filter(s -> "CON".equals(s.getSide())).count();
        String winner = proRounds == conRounds ? "DRAW" : (proRounds > conRounds ? "PRO" : "CON");
        return DebateResultVO.builder()
                .topic(topic).rounds(rounds).transcript(transcript)
                .verdict("按轮次论点计数：正方 " + proRounds + " 轮，反方 " + conRounds + " 轮，判 " + winner)
                .winner(winner).judgedByPort(false)
                .build();
    }

    /** 裁决文本含 PRO/CON 判定词时提取胜方（DRAW 平局），否则 DRAW */
    static String winnerFrom(String verdict) {
        String upper = verdict.toUpperCase();
        if (upper.contains("DRAW") || upper.contains("平局")) {
            return "DRAW";
        }
        if (upper.contains("PRO")) {
            return "PRO";
        }
        if (upper.contains("CON") && !upper.contains("PRO")) {
            return "CON";
        }
        return "DRAW";
    }

    private String safeSpeak(String side, String topic, int round, List<String> opponentPoints) {
        try {
            String content = speechPort.speak(side, topic, round, opponentPoints);
            if (content != null && !content.isBlank()) {
                return content;
            }
        } catch (RuntimeException ignored) {
            // 占位继续
        }
        return "（" + side + " 第" + round + "轮发言生成失败，占位继续）";
    }

    private List<String> lastConPoints(List<DebateSpeechVO> transcript) {
        return transcript.stream()
                .filter(s -> "CON".equals(s.getSide()))
                .map(DebateSpeechVO::getContent)
                .toList();
    }

    private List<String> lastProPoints(List<DebateSpeechVO> transcript) {
        return transcript.stream()
                .filter(s -> "PRO".equals(s.getSide()))
                .map(DebateSpeechVO::getContent)
                .toList();
    }
}
