package cn.chyuan.ai.domain.docintel.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 版面元素块（工单 0387 AV1）：页面元素框（左上原点，bbox+类型+文本）。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LayoutBlockVO {

    /** 元素类型常量 */
    public static final String TITLE = "TITLE";
    public static final String PARAGRAPH = "PARAGRAPH";
    public static final String TABLE = "TABLE";
    public static final String CAPTION = "CAPTION";

    /** 元素标识 */
    private String id;

    /** 左上 x */
    private double x;

    /** 左上 y */
    private double y;

    /** 宽 */
    private double width;

    /** 高 */
    private double height;

    /** 类型（TITLE/PARAGRAPH/TABLE/CAPTION） */
    private String type;

    /** 文本内容 */
    private String text;
}
