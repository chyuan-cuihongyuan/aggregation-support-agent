package cn.chyuan.ai.domain.crew.groupchat.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 群聊记分卡值对象（AN8：四类指标 + 发言均衡度，沿用 AF09 记分卡结构先例）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GroupChatScorecardVO {

    /** 轮次效率：达成终态所用轮次 / 轮次上限（空会话为 0） */
    private double roundEfficiency;

    /** token 成本合计 */
    private int totalTokens;

    /** 任务完成度：CONSENSUS=1.0 / TERMINATED=0.5 / 其他=0.0 */
    private double completionScore;

    /** 发言均衡度：1 - 最大份额偏离公平份额归一（独占=0，均衡=1） */
    private double balanceScore;

    /** 多会话共识达成率（单会话即 0/1） */
    private double consensusRate;

    /** 评估会话数 */
    private int sessionCount;
}
