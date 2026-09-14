package cn.chyuan.ai.domain.crew.groupchat.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * SOP 流水线结果值对象（AN6：逐环产物留痕 + 拒绝记录 + 通过率）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SopRunResultVO {

    /** 是否全链通过 */
    private boolean completed;

    /** 执行到的环节数（含被拒环节） */
    private int executedSteps;

    /** 逐环产物（角色 → 产物字段表） */
    private List<Map<String, Object>> artifacts;

    /** 逐环角色（与 artifacts 对齐） */
    private List<String> roles;

    /** 拒绝记录（环节序/角色/缺失字段） */
    private List<String> rejections;

    /** 全链通过率（通过环节数 / 总环节数） */
    private double passRate;
}
