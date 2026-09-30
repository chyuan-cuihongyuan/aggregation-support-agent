package cn.chyuan.ai.domain.iockernel.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Bean 定义注册表（工单 1119 EV1，spring-framework 思想）。
 * register 声明 name/类/作用域/重复 name 拒绝/别名注册/未知 bean 获取拒绝；
 * 别名可反查主名，注册序即遍历序。
 */
public final class BeanDefinitions {

    /** 作用域：单例容器缓存复用/原型每次新建 */
    public enum Scope { SINGLETON, PROTOTYPE }

    /** Bean 定义：主名 + 类名 + 作用域 + 别名集 */
    public record Definition(String name, String className, Scope scope, Set<String> aliases) {
    }

    private final Map<String, Definition> definitions = new LinkedHashMap<>();
    private final Map<String, String> aliasIndex = new LinkedHashMap<>();

    /** 注册定义：空名/空类名/重复 name 拒绝 */
    public Definition register(String name, String className, Scope scope) {
        if (name == null || name.isBlank() || className == null || className.isBlank()) {
            throw new IllegalArgumentException("bean 名与类名不能为空");
        }
        if (scope == null) {
            throw new IllegalArgumentException("作用域不能为空");
        }
        if (definitions.containsKey(name) || aliasIndex.containsKey(name)) {
            throw new IllegalArgumentException("重复 bean 名: " + name);
        }
        Definition definition = new Definition(name, className, scope, new LinkedHashSet<>());
        definitions.put(name, definition);
        return definition;
    }

    /** 注册别名：未知 bean 拒绝/别名与主名或既有别名冲突拒绝/自别名拒绝 */
    public void alias(String beanName, String alias) {
        Definition definition = require(beanName);
        if (alias == null || alias.isBlank() || alias.equals(beanName)) {
            throw new IllegalArgumentException("别名不能为空且不得与主名相同");
        }
        if (definitions.containsKey(alias) || aliasIndex.containsKey(alias)) {
            throw new IllegalArgumentException("重复别名: " + alias);
        }
        definition.aliases().add(alias);
        aliasIndex.put(alias, beanName);
    }

    /** 按主名或别名解析定义；未知返回 null */
    public Definition resolve(String nameOrAlias) {
        if (definitions.containsKey(nameOrAlias)) {
            return definitions.get(nameOrAlias);
        }
        String main = aliasIndex.get(nameOrAlias);
        return main == null ? null : definitions.get(main);
    }

    /** 取定义：未知 bean 拒绝 */
    public Definition require(String nameOrAlias) {
        Definition definition = resolve(nameOrAlias);
        if (definition == null) {
            throw new IllegalArgumentException("未知 bean: " + nameOrAlias);
        }
        return definition;
    }

    public boolean has(String name) {
        return resolve(name) != null;
    }

    /** 注册序主名列表（只读快照） */
    public java.util.List<String> names() {
        return java.util.List.copyOf(definitions.keySet());
    }
}
