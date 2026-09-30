package cn.chyuan.ai.domain.iockernel.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * IoC 容器组合实现（工单 1126 EV8，spring-framework 思想）。
 * get 装配序：创建实例→before 后处理→三级缓存暴露工厂→递归装配依赖→初始化留痕→
 * after 后处理→成品入一级缓存；单例 setter 环经早期引用放行，原型/在创建无早期引用判环拒绝。
 */
public final class IocContainer implements IocPort {

    private final BeanDefinitions definitions = new BeanDefinitions();
    private final DependencyGraph graph = new DependencyGraph();
    private final ThreeLevelCache cache = new ThreeLevelCache();
    private final PostProcessors postProcessors = new PostProcessors();
    private final LifecycleHooks lifecycle = new LifecycleHooks();
    private final EventMulticaster multicaster = new EventMulticaster();
    private final ConditionalProperties conditions = new ConditionalProperties();
    private final Set<String> inCreation = new HashSet<>();
    private final Set<String> skipped = new HashSet<>();
    private long seq;
    private boolean closed;

    @Override
    public void register(String name, String className, String scope) {
        assertOpen();
        if (!conditions.eligible(name)) {
            skipped.add(name);
            return;
        }
        skipped.remove(name);
        definitions.register(name, className, parseScope(scope));
    }

    @Override
    public boolean skipped(String name) {
        return skipped.contains(name);
    }

    @Override
    public void requireProperty(String beanName, String property, String expectedValue) {
        conditions.require(beanName, property, expectedValue);
    }

    @Override
    public void setProperty(String key, String value) {
        conditions.setProperty(key, value);
    }

    @Override
    public void alias(String beanName, String alias) {
        assertOpen();
        definitions.alias(beanName, alias);
    }

    @Override
    public void dependsOn(String beanName, String dependency) {
        assertOpen();
        if (!definitions.has(beanName)) {
            throw new IllegalArgumentException("未知 bean: " + beanName);
        }
        if (!definitions.has(dependency)) {
            throw new IllegalArgumentException("缺失依赖: " + dependency);
        }
        graph.dependsOn(beanName, dependency);
    }

    @Override
    public void addPostProcessor(PostProcessors.Processor processor) {
        assertOpen();
        postProcessors.register(processor);
    }

    @Override
    public void listener(String listenerName, String eventType) {
        assertOpen();
        multicaster.register(listenerName, eventType);
    }

    @Override
    public Instance get(String name) {
        assertOpen();
        BeanDefinitions.Definition definition = definitions.resolve(name);
        if (definition == null) {
            if (skipped.contains(name)) {
                throw new IllegalStateException("条件不满足未注册: " + name);
            }
            throw new IllegalArgumentException("未知 bean: " + name);
        }
        String beanName = definition.name();
        if (definition.scope() == BeanDefinitions.Scope.PROTOTYPE) {
            return createPrototype(definition);
        }
        Instance finished = cache.getSingleton(beanName);
        if (finished != null) {
            return finished;
        }
        if (inCreation.contains(beanName)) {
            Instance early = cache.getEarly(beanName);
            if (early == null) {
                throw new IllegalStateException("构造器循环依赖拒绝: " + beanName);
            }
            return early;
        }
        return createSingleton(definition);
    }

    @Override
    public List<String> publish(String eventType) {
        assertOpen();
        return new ArrayList<>(multicaster.publish(eventType).delivered());
    }

    @Override
    public List<String> close() {
        assertOpen();
        return lifecycle.close();
    }

    @Override
    public List<String> blocksShape() {
        return List.of("type", "labels", "attrs");
    }

    /** 原型：不进缓存，创建中再入即构造器环拒绝；同样走前后处理 */
    private Instance createPrototype(BeanDefinitions.Definition definition) {
        String beanName = definition.name();
        if (inCreation.contains(beanName)) {
            throw new IllegalStateException("原型构造器循环依赖拒绝: " + beanName);
        }
        Instance instance = newInstance(beanName, definition.scope());
        inCreation.add(beanName);
        try {
            for (String dep : graph.depsOf(beanName)) {
                get(dep);
            }
        } finally {
            inCreation.remove(beanName);
        }
        instance = postProcessors.before(beanName, instance);
        return postProcessors.after(beanName, instance);
    }

    /** 单例：创建后先暴露早期引用再装配依赖——setter 环由此放行 */
    private Instance createSingleton(BeanDefinitions.Definition definition) {
        String beanName = definition.name();
        Instance instance = newInstance(beanName, definition.scope());
        inCreation.add(beanName);
        try {
            instance = postProcessors.before(beanName, instance);
            cache.addFactory(beanName, instance);
            for (String dep : graph.depsOf(beanName)) {
                get(dep);
            }
            lifecycle.markInitialized(beanName);
            instance = postProcessors.after(beanName, instance);
            cache.putSingleton(beanName, instance);
        } finally {
            inCreation.remove(beanName);
            cache.removeFactory(beanName);
        }
        return instance;
    }

    private Instance newInstance(String beanName, BeanDefinitions.Scope scope) {
        return new Instance(beanName, scope.name().toLowerCase(), ++seq);
    }

    private BeanDefinitions.Scope parseScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return BeanDefinitions.Scope.SINGLETON;
        }
        try {
            return BeanDefinitions.Scope.valueOf(scope.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知作用域: " + scope);
        }
    }

    private void assertOpen() {
        if (closed || lifecycle.isClosed()) {
            throw new IllegalStateException("容器已关闭");
        }
    }
}
