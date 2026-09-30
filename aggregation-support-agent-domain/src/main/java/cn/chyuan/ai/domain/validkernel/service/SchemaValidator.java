package cn.chyuan.ai.domain.validkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 校验编排实现（工单 1166 FA8，pydantic 思想）。
 * 单遍扫描：缺失处置（必填/默认值）→null 处置（可空）→强转→类型核对→约束→
 * 嵌套递归（路径点拼接）→未知字段收集→自定义校验器；parse 失败聚合成一条 ISE。
 */
public final class SchemaValidator implements ValidPort {

    private final ModelSchema schema = new ModelSchema();
    private final CustomValidators validators = new CustomValidators();
    private ModelSchema.FieldSpec last;
    private boolean strict;
    private List<String> lastExtras = List.of();

    @Override
    public ValidPort field(String name, String type) {
        last = schema.required(name, type);
        return this;
    }

    @Override
    public ValidPort optional(String name, String type) {
        last = schema.optional(name, type);
        return this;
    }

    @Override
    public ValidPort withDefault(Object value) {
        last.withDefault(value);
        return this;
    }

    @Override
    public ValidPort nullable() {
        last.asNullable();
        return this;
    }

    @Override
    public ValidPort constrain(String kind, String expr) {
        last.withConstraints(Constraints.parse(kind, expr));
        return this;
    }

    @Override
    public ValidPort nested(ValidPort nested) {
        if (!(nested instanceof SchemaValidator validator)) {
            throw new IllegalArgumentException("嵌套须为同内核 Schema");
        }
        last.withNested(validator.schema);
        return this;
    }

    @Override
    public ValidPort alias(String alias) {
        last.withAlias(alias);
        return this;
    }

    @Override
    public ValidPort exclude() {
        last.asExcluded();
        return this;
    }

    @Override
    public ValidPort strict() {
        this.strict = true;
        return this;
    }

    @Override
    public List<String> validate(Map<String, Object> document) {
        List<String> failures = new ArrayList<>();
        List<String> extras = new ArrayList<>();
        walk(document, schema, "", new LinkedHashMap<>(), failures, extras);
        lastExtras = List.copyOf(extras);
        return List.copyOf(failures);
    }

    @Override
    public Map<String, Object> parse(Map<String, Object> document) {
        List<String> failures = new ArrayList<>();
        List<String> extras = new ArrayList<>();
        Map<String, Object> instance = new LinkedHashMap<>();
        walk(document, schema, "", instance, failures, extras);
        validators.runModel(instance, failures);
        lastExtras = List.copyOf(extras);
        if (!failures.isEmpty()) {
            throw new IllegalStateException("校验失败: " + String.join("; ", failures));
        }
        return instance;
    }

    @Override
    public List<String> lastExtras() {
        return lastExtras;
    }

    @Override
    public Map<String, Object> dump(Map<String, Object> instance) {
        return Serializer.dump(instance, schema);
    }

    @Override
    public ValidPort fieldValidator(String field, CustomValidators.FieldValidator validator) {
        validators.addField(field, validator);
        return this;
    }

    @Override
    public ValidPort modelValidator(CustomValidators.ModelValidator validator) {
        validators.addModel(validator);
        return this;
    }

    @Override
    public List<String> documentShape() {
        return List.of(".", ".key", ".key[0]");
    }

    /** 单遍扫描：缺失处置→null 处置→强转→类型→约束→嵌套递归→未知字段→顶层跑自定义校验器 */
    private void walk(Map<String, Object> document, ModelSchema target, String prefix,
                      Map<String, Object> instance, List<String> failures, List<String> extras) {
        for (ModelSchema.FieldSpec spec : target.fieldList()) {
            String path = prefix.isEmpty() ? spec.name : prefix + "." + spec.name;
            if (!document.containsKey(spec.name)) {
                Defaults.handleMissing(failures, path, instance, spec);
                continue;
            }
            Object value = document.get(spec.name);
            if (value == null) {
                if (Defaults.handleNull(failures, path, spec)) {
                    continue;
                }
                instance.put(spec.name, null);
                continue;
            }
            Object coerced;
            try {
                coerced = coerceValue(value, spec);
            } catch (RuntimeException e) {
                failures.add(path + " " + e.getMessage());
                continue;
            }
            if (!FieldTypes.matches(coerced, spec.type)) {
                failures.add(path + " 类型不匹配: 期望 " + spec.type + " 实际 " + FieldTypes.kindOf(coerced));
                continue;
            }
            if (!spec.constraints.empty()) {
                try {
                    spec.constraints.check(path, coerced);
                } catch (RuntimeException e) {
                    failures.add(e.getMessage());
                    continue;
                }
            }
            if (spec.nested != null && coerced instanceof Map<?, ?> nestedDoc) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedInstance = new LinkedHashMap<>((Map<String, Object>) nestedDoc);
                walk((Map<String, Object>) nestedDoc, spec.nested, path, nestedInstance, failures, extras);
                instance.put(spec.name, nestedInstance);
                continue;
            }
            instance.put(spec.name, coerced);
        }
        for (String key : document.keySet()) {
            if (!target.has(key)) {
                extras.add(prefix.isEmpty() ? key : prefix + "." + key);
            }
        }
        if (prefix.isEmpty()) {
            for (ModelSchema.FieldSpec spec : target.fieldList()) {
                if (instance.containsKey(spec.name)) {
                    validators.runField(spec.name, instance.get(spec.name), failures);
                }
            }
            validators.runModel(instance, failures);
        }
    }

    private Object coerceValue(Object value, ModelSchema.FieldSpec spec) {
        String elementType = FieldTypes.elementType(spec.type);
        if (elementType != null && value instanceof List<?> list) {
            return Coercions.coerceList(list, elementType, strict);
        }
        return Coercions.coerce(value, spec.type, strict);
    }
}
