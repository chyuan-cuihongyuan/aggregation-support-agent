package cn.chyuan.ai.domain.crew.groupchat.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 群聊回放视图值对象（AN7：会话元数据 + 逐轮重放序列）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GroupChatReplayViewVO {

    /** 会话ID */
    private String sessionId;

    /** 参与者（注册序） */
    private List<String> participants;

    /** 轮次上限 */
    private int maxRounds;

    /** 终态（CONSENSUS/TERMINATED/EXCEEDED，未结束为 RUNNING） */
    private String finalStatus;

    /** 终止原因 */
    private String endReason;

    /** 重放序列（按消息追加序，逐轮发言） */
    private List<GroupChatMessageVO> replaySequence;

    /** 总轮次 */
    private int totalRounds;

    /** token 估算合计 */
    private int totalTokens;
}
