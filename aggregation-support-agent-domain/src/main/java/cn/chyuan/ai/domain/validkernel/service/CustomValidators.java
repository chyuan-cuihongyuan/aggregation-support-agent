package cn.chyuan.ai.domain.validkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自定义校验器（工单 1164 FA6，pydantic 思想）。
 * 字段级与模型级校验器按注册序执行；校验器抛出的异常转校验失败而非中断。
 */
public final class CustomValidators {

    /** 字段级校验器：返回 null 通过，返回消息即失败 */
    public interface FieldValidator {
        String check(String field, Object value);
    }

    /** 模型级跨字段校验器：返回 null 通过，返回消息即失败 */
    public interface ModelValidator {
        String check(Map<String, Object> instance);
    }

    private final Map<String, List<FieldValidator>> fieldValidators = new LinkedHashMap<>();
    private final List<ModelValidator> modelValidators = new ArrayList<>();
    private final List<String> trace = new ArrayList<>();

    /** 注册字段级校验器（同字段多校验器按注册序） */
    public void addField(String field, FieldValidator validator) {
        if (field == null || field.isBlank() || validator == null) {
            throw new IllegalArgumentException("字段与校验器不能为空");
        }
        fieldValidators.computeIfAbsent(field, k -> new ArrayList<>()).add(validator);
    }

    /** 注册模型级校验器 */
    public void addModel(ModelValidator validator) {
        if (validator == null) {
            throw new IllegalArgumentException("校验器不能为空");
        }
        modelValidators.add(validator);
    }

    /** 执行某字段的全部校验器：异常转失败，按序收集 */
    public void runField(String field, Object value, List<String> failures) {
        for (FieldValidator validator : fieldValidators.getOrDefault(field, List.of())) {
            try {
                String message = validator.check(field, value);
                trace.add("field:" + field);
                if (message != null) {
                    failures.add(field + " " + message);
                }
            } catch (RuntimeException e) {
                trace.add("field:" + field);
                failures.add(field + " 校验器异常: " + e.getMessage());
            }
        }
    }

    /** 执行全部模型级校验器 */
    public void runModel(Map<String, Object> instance, List<String> failures) {
        for (ModelValidator validator : modelValidators) {
            try {
                String message = validator.check(instance);
                trace.add("model");
                if (message != null) {
                    failures.add(message);
                }
            } catch (RuntimeException e) {
                trace.add("model");
                failures.add("模型校验器异常: " + e.getMessage());
            }
        }
    }

    public boolean hasFieldValidators(String field) {
        return fieldValidators.containsKey(field) && !fieldValidators.get(field).isEmpty();
    }

    public int modelCount() {
        return modelValidators.size();
    }

    /** 执行轨迹（注册/调用序，只读） */
    public List<String> trace() {
        return List.copyOf(trace);
    }
}
