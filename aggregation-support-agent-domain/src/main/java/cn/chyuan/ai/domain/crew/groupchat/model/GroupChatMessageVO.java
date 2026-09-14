package cn.chyuan.ai.domain.crew.groupchat.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 群聊消息值对象（AN1：逐轮发言留痕）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GroupChatMessageVO {

    /** 轮次（从 1 起） */
    private int round;

    /** 发言者角色名 */
    private String speaker;

    /** 发言内容 */
    private String content;

    /** 触发策略（round-robin/weighted/auto/manual） */
    private String triggerStrategy;

    /** token 估算（字符近似） */
    private int tokenEstimate;
}
