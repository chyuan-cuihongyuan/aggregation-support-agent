package cn.chyuan.ai.domain.browser.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 可交互元素值对象（AQ2：role/name/selector/位置/可编辑性）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ElementVO {

    /** 元素ID（快照内唯一） */
    private String elementId;

    /** 角色：button/input/link/select/text 等 */
    private String role;

    /** 可访问名 */
    private String name;

    /** 选择器 */
    private String selector;

    /** 位置矩形 x,y,w,h */
    private int x;
    private int y;
    private int width;
    private int height;

    /** 是否启用 */
    private boolean enabled;

    /** 是否可编辑 */
    private boolean editable;
}
