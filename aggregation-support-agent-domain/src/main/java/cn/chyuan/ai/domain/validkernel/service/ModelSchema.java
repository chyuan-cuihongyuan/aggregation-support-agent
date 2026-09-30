package cn.chyuan.ai.domain.validkernel.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型 Schema 与字段规格（工单 1162 FA4，pydantic 思想）。
 * 字段有序声明；嵌套对象挂子 Schema 递归校验；错误路径以点拼接、列表以 [i] 拼接。
 */
public final class ModelSchema {

    /** 字段规格 */
    public static final class FieldSpec {
        public final String name;
        public final String type;
        public final boolean required;
        public boolean nullable;
        public Object defaultValue;
        public Constraints constraints = Constraints.none();
        public ModelSchema nested;
        public String alias;
        public boolean excluded;

        FieldSpec(String name, String type, boolean required) {
            if (!FieldTypes.validDeclaration(type)) {
                throw new IllegalArgumentException("未知类型声明: " + type);
            }
            this.name = name;
            this.type = type;
            this.required = required;
        }

        public FieldSpec withDefault(Object value) {
            this.defaultValue = value;
            return this;
        }

        public FieldSpec asNullable() {
            this.nullable = true;
            return this;
        }

        public FieldSpec withConstraints(Constraints constraints) {
            this.constraints = constraints;
            return this;
        }

        public FieldSpec withNested(ModelSchema nested) {
            this.nested = nested;
            return this;
        }

        public FieldSpec withAlias(String alias) {
            this.alias = alias;
            return this;
        }

        public FieldSpec asExcluded() {
            this.excluded = true;
            return this;
        }
    }

    private final Map<String, FieldSpec> fields = new LinkedHashMap<>();

    /** 必填字段 */
    public FieldSpec required(String name, String type) {
        return put(name, type, true);
    }

    /** 可选字段 */
    public FieldSpec optional(String name, String type) {
        return put(name, type, false);
    }

    private FieldSpec put(String name, String type, boolean required) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("字段名不能为空");
        }
        if (fields.containsKey(name)) {
            throw new IllegalArgumentException("重复字段: " + name);
        }
        FieldSpec spec = new FieldSpec(name, type, required);
        fields.put(name, spec);
        return spec;
    }

    public java.util.List<FieldSpec> fieldList() {
        return java.util.List.copyOf(fields.values());
    }

    public FieldSpec field(String name) {
        return fields.get(name);
    }

    public boolean has(String name) {
        return fields.containsKey(name);
    }
}
