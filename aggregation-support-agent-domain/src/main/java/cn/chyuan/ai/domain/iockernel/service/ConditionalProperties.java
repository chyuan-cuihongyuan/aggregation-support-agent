package cn.chyuan.ai.domain.iockernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 条件装配（工单 1125 EV7，spring-framework 思想）。
 * require 为 bean 挂条件（属性键=期望值），多条件 AND 全过才可注册；
 * 谓词不匹配由容器跳过注册并留痕，获取被跳过 bean 拒绝。
 */
public final class ConditionalProperties {

    /** 条件：属性键 + 期望值 */
    public record Condition(String property, String expectedValue) {
    }

    private final Map<String, List<Condition>> conditions = new LinkedHashMap<>();
    private final Map<String, String> properties = new LinkedHashMap<>();

    /** 挂条件：空参拒绝；同 bean 多条件 AND */
    public void require(String beanName, String property, String expectedValue) {
        if (beanName == null || beanName.isBlank() || property == null || property.isBlank()
                || expectedValue == null || expectedValue.isBlank()) {
            throw new IllegalArgumentException("条件三要素不能为空");
        }
        conditions.computeIfAbsent(beanName, k -> new ArrayList<>()).add(new Condition(property, expectedValue));
    }

    /** 设置属性开关 */
    public void setProperty(String key, String value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("属性键不能为空");
        }
        properties.put(key, value);
    }

    /** 全条件满足才 eligible；无条件视为满足；任一不满足（含属性缺失）即否 */
    public boolean eligible(String beanName) {
        for (Condition condition : conditions.getOrDefault(beanName, List.of())) {
            if (!properties.containsKey(condition.property())
                    || !properties.get(condition.property()).equals(condition.expectedValue())) {
                return false;
            }
        }
        return true;
    }

    /** 未满足的条件列表（供跳过留痕与诊断） */
    public List<Condition> unmet(String beanName) {
        List<Condition> unmet = new ArrayList<>();
        for (Condition condition : conditions.getOrDefault(beanName, List.of())) {
            if (!properties.containsKey(condition.property())
                    || !properties.get(condition.property()).equals(condition.expectedValue())) {
                unmet.add(condition);
            }
        }
        return List.copyOf(unmet);
    }

    public boolean hasConditions(String beanName) {
        return conditions.containsKey(beanName) && !conditions.get(beanName).isEmpty();
    }
}
