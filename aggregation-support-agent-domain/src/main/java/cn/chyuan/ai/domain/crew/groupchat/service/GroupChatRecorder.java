package cn.chyuan.ai.domain.crew.groupchat.service;

import cn.chyuan.ai.domain.crew.groupchat.model.GroupChatMessageVO;
import cn.chyuan.ai.domain.crew.groupchat.model.GroupChatReplayViewVO;

import java.util.ArrayList;
import java.util.List;

/**
 * 群聊回放审计记录器（工单 0321 AN7）。
 * 群聊会话 → 回放视图（会话元数据+按追加序的逐轮发言序列），重放确定性
 * （重放序列与原始会话一致）；复用五期 CrewRunRecorder 留痕先例的群聊化扩展。
 * domain 纯函数。
 */
public class GroupChatRecorder {

    /** 由状态机会话生成回放视图 */
    public GroupChatReplayViewVO record(String sessionId, GroupChatSession session) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("会话ID不能为空");
        }
        List<GroupChatMessageVO> sequence = new ArrayList<>(session.getMessages());
        int totalTokens = sequence.stream().mapToInt(GroupChatMessageVO::getTokenEstimate).sum();
        return GroupChatReplayViewVO.builder()
                .sessionId(sessionId)
                .participants(List.copyOf(session.getParticipants()))
                .maxRounds(session.getMaxRounds())
                .finalStatus(session.getStatus().name())
                .endReason(session.getEndReason())
                .replaySequence(sequence)
                .totalRounds(sequence.isEmpty() ? 0
                        : sequence.get(sequence.size() - 1).getRound())
                .totalTokens(totalTokens)
                .build();
    }

    /** 重放校验：回放序列与原始会话历史完全一致（确定性） */
    public boolean replayMatches(GroupChatSession session, GroupChatReplayViewVO view) {
        List<GroupChatMessageVO> messages = session.getMessages();
        if (messages.size() != view.getReplaySequence().size()) {
            return false;
        }
        for (int i = 0; i < messages.size(); i++) {
            GroupChatMessageVO original = messages.get(i);
            GroupChatMessageVO replayed = view.getReplaySequence().get(i);
            if (replayed == null
                    || original.getRound() != replayed.getRound()
                    || !original.getSpeaker().equals(replayed.getSpeaker())
                    || !original.getContent().equals(replayed.getContent())
                    || !original.getTriggerStrategy().equals(replayed.getTriggerStrategy())) {
                return false;
            }
        }
        return true;
    }
}
