package cn.chyuan.ai.domain.browser.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 结构化抽取结果值对象（AQ5：字段值 + 缺失字段清单）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ExtractionResultVO {

    /** 是否全部必填字段抽取成功 */
    private boolean success;

    /** 字段值（字段名 → 值，多值为 List） */
    private Map<String, Object> values;

    /** 缺失/类型不符字段清单（字段名:原因） */
    private List<String> missing;

    /** 抽取 schema 字段定义 */
    public record FieldSpec(String name, String type, boolean multiValue, boolean required) {
    }
}
