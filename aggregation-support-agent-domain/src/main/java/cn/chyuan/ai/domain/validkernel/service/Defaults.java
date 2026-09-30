package cn.chyuan.ai.domain.validkernel.service;

import java.util.Map;

/**
 * 默认值与可空语义（工单 1163 FA5，pydantic 思想）。
 * 可选缺失以默认值填充；显式值不覆盖；null 仅可空类型接受。
 */
public final class Defaults {

    private Defaults() {
    }

    /** 填充默认值：返回新实例（不污染入参文档）；显式值保留原语义 */
    public static void apply(Map<String, Object> instance, ModelSchema.FieldSpec spec, Object value) {
        if (value != null) {
            instance.put(spec.name, value);
            return;
        }
        instance.put(spec.name, spec.defaultValue);
    }

    /** 缺失字段处置：必填缺失报 true（失败）；可选带默认填充 */
    public static boolean handleMissing(java.util.List<String> failures, String path,
                                        Map<String, Object> instance, ModelSchema.FieldSpec spec) {
        if (spec.required && spec.defaultValue == null) {
            failures.add(path + " 必填缺失");
            return true;
        }
        if (spec.defaultValue != null) {
            instance.put(spec.name, spec.defaultValue);
        }
        return false;
    }

    /** null 处置：可空通过；不可空报失败 */
    public static boolean handleNull(java.util.List<String> failures, String path, ModelSchema.FieldSpec spec) {
        if (spec.nullable) {
            return false;
        }
        failures.add(path + " 不接受 null");
        return true;
    }
}
