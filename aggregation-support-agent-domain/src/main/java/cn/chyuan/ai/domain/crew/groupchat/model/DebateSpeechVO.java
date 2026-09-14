package cn.chyuan.ai.domain.crew.groupchat.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 辩论发言值对象（AN5：PRO=正方 / CON=反方 / JUDGE=裁判点评）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DebateSpeechVO {

    /** 轮次（从 1 起） */
    private int round;

    /** 立场：PRO / CON / JUDGE */
    private String side;

    /** 发言内容 */
    private String content;
}
