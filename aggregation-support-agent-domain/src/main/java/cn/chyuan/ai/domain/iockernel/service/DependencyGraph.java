package cn.chyuan.ai.domain.iockernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 依赖图（工单 1120 EV2，spring-framework 思想）。
 * dependsOn 声明构造器/装配依赖；依赖的依赖由容器递归解析；自引用拒绝；
 * 依赖序即声明序，递归装配顺序 依赖→被依赖方 逐层创建。
 */
public final class DependencyGraph {

    private final Map<String, List<String>> edges = new LinkedHashMap<>();

    /** 声明依赖边：自引用拒绝/重复依赖幂等跳过 */
    public void dependsOn(String beanName, String dependency) {
        if (beanName == null || beanName.isBlank() || dependency == null || dependency.isBlank()) {
            throw new IllegalArgumentException("依赖边两端不能为空");
        }
        if (beanName.equals(dependency)) {
            throw new IllegalArgumentException("自引用依赖拒绝: " + beanName);
        }
        List<String> deps = edges.computeIfAbsent(beanName, k -> new ArrayList<>());
        if (!deps.contains(dependency)) {
            deps.add(dependency);
        }
    }

    /** 依赖列表（声明序，只读） */
    public List<String> depsOf(String beanName) {
        return List.copyOf(edges.getOrDefault(beanName, List.of()));
    }

    /** 递归展平后置依赖（去重，深序优先，供校验展示；装配本身由容器带环保护递归） */
    public List<String> deepDepsOf(String beanName) {
        List<String> result = new ArrayList<>();
        collect(beanName, result);
        return List.copyOf(result);
    }

    private void collect(String beanName, List<String> result) {
        for (String dep : edges.getOrDefault(beanName, List.of())) {
            collect(dep, result);
            if (!result.contains(dep)) {
                result.add(dep);
            }
        }
    }
}
