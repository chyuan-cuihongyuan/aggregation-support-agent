package cn.chyuan.ai.domain.browser.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 浏览器任务模板值对象（AQ6：目标+参数占位符+动作序列模板，第 19 表落库形态）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BrowserTaskTemplateVO {

    /** 模板名（唯一） */
    private String name;

    /** 目标描述 */
    private String goal;

    /** 参数占位符表（占位名 → 说明） */
    private Map<String, String> parameters;

    /** 动作序列模板（字段值可含 {{param}} 占位） */
    private List<BrowserActionVO> actions;
}
