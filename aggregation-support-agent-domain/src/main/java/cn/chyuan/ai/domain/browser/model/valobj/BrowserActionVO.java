package cn.chyuan.ai.domain.browser.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 浏览器动作值对象（AQ1：判别字段+参数，browser-use 动作空间思想）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BrowserActionVO {

    /** 动作类型常量 */
    public static final String NAVIGATE = "navigate";
    public static final String CLICK = "click";
    public static final String TYPE = "type";
    public static final String SCROLL = "scroll";
    public static final String EXTRACT = "extract";
    public static final String WAIT = "wait";

    /** 动作类型：navigate/click/type/scroll/extract/wait */
    private String type;

    /** 目标选择器（click/type/scroll 必填） */
    private String selector;

    /** 输入文本（type 必填） */
    private String text;

    /** 目标 URL（navigate 必填，http/https 白名单） */
    private String url;

    /** 滚动方向（scroll：up/down） */
    private String direction;

    /** 滚动量（scroll：像素，限幅 0-10000） */
    private Integer amount;

    /** 抽取字段表（extract：字段名 → 来源选择器约束） */
    private Map<String, String> fields;

    /** 等待毫秒（wait：上限 30000） */
    private Integer waitMs;

    /** 是否可跳过（失败恢复用，AQ8） */
    private boolean skippable;

    /** 合法动作类型集合 */
    public static final List<String> TYPES =
            List.of(NAVIGATE, CLICK, TYPE, SCROLL, EXTRACT, WAIT);
}
