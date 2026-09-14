package cn.chyuan.ai.domain.browser.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 动作校验错误值对象（AQ1：字段路径 + 原因）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ActionErrorVO {

    /** 错误字段路径（type/selector/text/url/direction/amount/fields/waitMs） */
    private String field;

    /** 原因：TYPE_UNKNOWN / REQUIRED_MISSING / RANGE_VIOLATION / SCHEME_REJECTED */
    private String code;
}
