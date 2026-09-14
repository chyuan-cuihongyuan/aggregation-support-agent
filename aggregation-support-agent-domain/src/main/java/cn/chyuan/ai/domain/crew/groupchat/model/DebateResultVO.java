package cn.chyuan.ai.domain.crew.groupchat.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 辩论结果值对象（AN5：发言留痕 + 裁决）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DebateResultVO {

    /** 议题 */
    private String topic;

    /** 计划轮数 */
    private int rounds;

    /** 逐轮发言（PRO→CON→JUDGE 交替留痕） */
    private List<DebateSpeechVO> transcript;

    /** 裁决文本（胜方+理由；模板兜底为按轮次论点判定） */
    private String verdict;

    /** 胜方：PRO / CON / DRAW（模板兜底平局） */
    private String winner;

    /** 裁决是否来自端口（false=模板兜底） */
    private boolean judgedByPort;
}
