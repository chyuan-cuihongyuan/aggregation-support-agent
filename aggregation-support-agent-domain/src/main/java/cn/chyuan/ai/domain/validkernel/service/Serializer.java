package cn.chyuan.ai.domain.validkernel.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 序列化（工单 1165 FA7，pydantic 思想）。
 * 按字段序导出 map；excluded 字段不导出；别名替换键名；嵌套对象递归导出。
 */
public final class Serializer {

    private Serializer() {
    }

    /** 导出：instance 键须与 schema 字段名一致 */
    public static Map<String, Object> dump(Map<String, Object> instance, ModelSchema schema) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ModelSchema.FieldSpec spec : schema.fieldList()) {
            if (spec.excluded || !instance.containsKey(spec.name)) {
                continue;
            }
            Object value = instance.get(spec.name);
            if (spec.nested != null && value instanceof Map<?, ?> nestedInstance) {
                @SuppressWarnings("unchecked")
                Map<String, Object> dumped = dump((Map<String, Object>) nestedInstance, spec.nested);
                out.put(spec.alias != null ? spec.alias : spec.name, dumped);
            } else {
                out.put(spec.alias != null ? spec.alias : spec.name, value);
            }
        }
        return out;
    }
}
