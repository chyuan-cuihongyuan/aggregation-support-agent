package cn.chyuan.ai.domain.crew.groupchat.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 反思循环结果值对象（AN4：Reflexion 轨迹）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ReflexionResultVO {

    /** 是否成功（评分达标） */
    private boolean success;

    /** 最终输出（失败为最后一次输出） */
    private String output;

    /** 最终得分 */
    private int score;

    /** 实际迭代次数 */
    private int iterations;

    /** 反思经验列表（按轮序） */
    private List<String> lessons;

    /** 轨迹（iter:score:output摘要） */
    private List<String> trace;
}
