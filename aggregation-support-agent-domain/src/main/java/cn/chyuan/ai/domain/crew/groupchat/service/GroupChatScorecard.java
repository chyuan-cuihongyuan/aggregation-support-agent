package cn.chyuan.ai.domain.crew.groupchat.service;

import cn.chyuan.ai.domain.crew.groupchat.model.GroupChatMessageVO;
import cn.chyuan.ai.domain.crew.groupchat.model.GroupChatReplayViewVO;
import cn.chyuan.ai.domain.crew.groupchat.model.GroupChatScorecardVO;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 群聊记分卡（工单 0322 AN8）。
 * 基于回放视图计算：轮次效率/token 成本/任务完成度（终态加权）/发言均衡度
 * （1-基尼系数）；多会话统计共识达成率。domain 纯函数。
 */
public class GroupChatScorecard {

    /** 单会话记分 */
    public GroupChatScorecardVO score(GroupChatReplayViewVO view) {
        if (view == null) {
            throw new IllegalArgumentException("回放视图不能为空");
        }
        double roundEfficiency = view.getMaxRounds() == 0 || view.getTotalRounds() == 0
                ? 0
                : (double) view.getTotalRounds() / view.getMaxRounds();
        double completion = switch (view.getFinalStatus() == null ? "" : view.getFinalStatus()) {
            case "CONSENSUS" -> 1.0;
            case "TERMINATED" -> 0.5;
            default -> 0.0;
        };
        return GroupChatScorecardVO.builder()
                .roundEfficiency(roundEfficiency)
                .totalTokens(view.getTotalTokens())
                .completionScore(completion)
                .balanceScore(balanceScore(view))
                .consensusRate("CONSENSUS".equals(view.getFinalStatus()) ? 1.0 : 0.0)
                .sessionCount(1)
                .build();
    }

    /** 多会话汇总：均值合成，共识率为达成会话占比 */
    public GroupChatScorecardVO aggregate(List<GroupChatReplayViewVO> views) {
        if (views == null || views.isEmpty()) {
            throw new IllegalArgumentException("回放视图列表不能为空");
        }
        double efficiency = views.stream().mapToDouble(v -> score(v).getRoundEfficiency()).average().orElse(0);
        int tokens = views.stream().mapToInt(GroupChatReplayViewVO::getTotalTokens).sum();
        double completion = views.stream().mapToDouble(v -> score(v).getCompletionScore()).average().orElse(0);
        double balance = views.stream().mapToDouble(v -> score(v).getBalanceScore()).average().orElse(0);
        long consensus = views.stream().filter(v -> "CONSENSUS".equals(v.getFinalStatus())).count();
        return GroupChatScorecardVO.builder()
                .roundEfficiency(efficiency)
                .totalTokens(tokens)
                .completionScore(completion)
                .balanceScore(balance)
                .consensusRate((double) consensus / views.size())
                .sessionCount(views.size())
                .build();
    }

    /** 发言均衡度：1 - 最大份额偏离公平份额的归一化（独占→0，均衡→1，空会话→0） */
    double balanceScore(GroupChatReplayViewVO view) {
        List<GroupChatMessageVO> messages = view.getReplaySequence();
        int participants = view.getParticipants().size();
        if (messages.isEmpty() || participants == 0) {
            return 0.0;
        }
        Map<String, Integer> counts = new HashMap<>();
        view.getParticipants().forEach(p -> counts.put(p, 0));
        messages.forEach(m -> counts.merge(m.getSpeaker(), 1, Integer::sum));
        int max = counts.values().stream().max(Comparator.naturalOrder()).orElse(0);
        if (max == 0) {
            return 0.0;
        }
        double maxShare = (double) max / messages.size();
        double fairShare = 1.0 / participants;
        // 偏离归一：完全公平 maxShare=fairShare → 0；独占 maxShare=1 → 1
        double deviation = (maxShare - fairShare) / (1.0 - fairShare);
        return 1.0 - deviation;
    }
}
