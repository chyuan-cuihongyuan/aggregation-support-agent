package cn.chyuan.ai.domain.iockernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IoC 容器内核测试（工单 1119-1126 EV1-EV8，spring-framework 思想）。
 * Bean 定义注册/依赖注入/三级缓存循环依赖/BeanPostProcessor/生命周期/事件广播/条件装配/端口组合管线。
 */
class IocKernelTest {

    @Test
    void beanRegistry() {
        BeanDefinitions registry = new BeanDefinitions();
        registry.register("order", "OrderService", BeanDefinitions.Scope.SINGLETON);
        assertThrows(IllegalArgumentException.class, () -> registry.register("order", "Dup", BeanDefinitions.Scope.SINGLETON), "重复 name 拒绝");
        assertThrows(IllegalArgumentException.class, () -> registry.register(" ", "Blank", BeanDefinitions.Scope.SINGLETON), "空名拒绝");
        registry.alias("order", "svc");
        assertEquals("order", registry.resolve("svc").name(), "别名反查主名");
        assertThrows(IllegalArgumentException.class, () -> registry.alias("order", "svc"), "重复别名拒绝");
        assertThrows(IllegalArgumentException.class, () -> registry.alias("nope", "x"), "未知 bean 挂别名拒绝");
        assertEquals("OrderService", registry.require("order").className());
        assertThrows(IllegalArgumentException.class, () -> registry.require("ghost"), "未知 bean 获取拒绝");
        assertEquals(List.of("order"), registry.names());
    }

    @Test
    void dependencyInjection() {
        IocPort port = IocPort.inMemory();
        port.register("c", "Repo", "singleton");
        port.register("b", "Service", "singleton");
        port.register("a", "Facade", "singleton");
        port.dependsOn("a", "b");
        port.dependsOn("b", "c");
        Instance a = port.get("a");
        assertEquals(1, a.seq(), "根先创建（setter 注入语义）");
        assertNotNull(port.get("b"), "递归装配连带 b 成品化");
        assertNotNull(port.get("c"), "依赖的依赖 c 亦成品化");
        assertSame(a, port.get("a"), "单例复用同实例");
        assertThrows(IllegalArgumentException.class, () -> port.dependsOn("a", "ghost"), "缺失依赖声明拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.get("ghost"), "未知 bean 获取拒绝");

        port.register("proto", "P", "prototype");
        Instance first = port.get("proto");
        Instance second = port.get("proto");
        assertNotEquals(first.seq(), second.seq(), "原型每次新实例");
    }

    @Test
    void circularDependencies() {
        IocPort port = IocPort.inMemory();
        port.register("a", "A", "singleton");
        port.register("b", "B", "singleton");
        port.dependsOn("a", "b");
        port.dependsOn("b", "a");
        Instance a = port.get("a");
        assertEquals(a.seq(), port.get("a").seq(), "setter 环放行后成品一致");
        assertNotNull(port.get("b"));

        port.register("p", "P", "prototype");
        port.register("q", "Q", "prototype");
        port.dependsOn("p", "q");
        port.dependsOn("q", "p");
        assertThrows(IllegalStateException.class, () -> port.get("p"), "原型构造器环拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.dependsOn("a", "a"), "自引用拒绝");
    }

    @Test
    void cacheLevels() {
        ThreeLevelCache cache = new ThreeLevelCache();
        assertNull(cache.getEarly("x"), "全空探查返回 null（构造器环信号）");
        cache.addFactory("x", new Instance("x", "singleton", 1));
        assertThrows(IllegalStateException.class, () -> cache.addFactory("x", new Instance("x", "singleton", 2)), "重复暴露早期引用拒绝");
        Instance early = cache.getEarly("x");
        assertEquals(1, early.seq());
        assertTrue(cache.inEarlyExposure("x"), "三级命中迁移二级后仍处暴露态");
        cache.putSingleton("x", early);
        assertSame(early, cache.getSingleton("x"), "成品入一级缓存");
        assertEquals(1, cache.singletonCount());
    }

    @Test
    void postProcessorChain() {
        IocPort port = IocPort.inMemory();
        port.register("svc", "S", "singleton");
        port.addPostProcessor(new PostProcessors.Processor() {
            @Override
            public Instance before(String beanName, Instance bean) {
                return new Instance(beanName, bean.scope(), bean.seq() + 1000);
            }

            @Override
            public Instance after(String beanName, Instance bean) {
                return null;
            }
        });
        port.addPostProcessor(new PostProcessors.Processor() {
            @Override
            public Instance before(String beanName, Instance bean) {
                return null;
            }

            @Override
            public Instance after(String beanName, Instance bean) {
                return new Instance(beanName, bean.scope(), bean.seq() + 5);
            }
        });
        Instance svc = port.get("svc");
        assertEquals(1006, svc.seq(), "before 替换 +1k、after 再 +5，按注册序折叠");
    }

    @Test
    void lifecycleClose() {
        LifecycleHooks hooks = new LifecycleHooks();
        hooks.markInitialized("db");
        hooks.markInitialized("svc");
        assertThrows(IllegalStateException.class, () -> hooks.markInitialized("db"), "重复初始化拒绝");
        hooks.destroy("db");
        assertThrows(IllegalStateException.class, () -> hooks.destroy("db"), "重复销毁拒绝");
        assertThrows(IllegalArgumentException.class, () -> hooks.destroy("ghost"), "未初始化销毁拒绝");
        assertEquals(List.of("svc"), hooks.close(), "关闭只销毁未销毁者且逆序");
        assertThrows(IllegalStateException.class, hooks::close, "重复关闭拒绝");

        IocPort port = IocPort.inMemory();
        port.register("db", "Db", "singleton");
        port.register("svc", "Svc", "singleton");
        port.dependsOn("svc", "db");
        port.get("svc");
        assertEquals(List.of("svc", "db"), port.close(), "关闭逆序销毁（依赖先成、宿主先灭）");
        assertThrows(IllegalStateException.class, () -> port.get("db"), "关闭后获取拒绝");
        assertThrows(IllegalStateException.class, () -> port.register("late", "L", "singleton"), "关闭后注册拒绝");
    }

    @Test
    void eventBroadcast() {
        EventMulticaster multicaster = new EventMulticaster();
        multicaster.register("l1", "order.created");
        multicaster.register("l2", "order.created");
        multicaster.register("l3", "order.paid");
        assertThrows(IllegalArgumentException.class, () -> multicaster.register("l1", "dup"), "重复监听器拒绝");
        EventMulticaster.DeliveryReport report = multicaster.publish("order.created");
        assertEquals(List.of("l1", "l2"), report.delivered(), "注册序投递且类型过滤");
        multicaster.failNext("l1");
        EventMulticaster.DeliveryReport isolated = multicaster.publish("order.created");
        assertEquals(List.of("l2"), isolated.delivered(), "异常监听器隔离不影响后续");
        assertEquals(List.of("l1"), isolated.failed());
        assertThrows(IllegalArgumentException.class, () -> multicaster.failNext("ghost"), "未知监听器标记拒绝");

        IocPort port = IocPort.inMemory();
        port.listener("a", "evt");
        port.listener("b", "evt");
        assertEquals(List.of("a", "b"), port.publish("evt"), "端口发布按注册序");
        assertTrue(port.publish("none").isEmpty());
    }

    @Test
    void conditionalWiring() {
        ConditionalProperties conditions = new ConditionalProperties();
        conditions.require("m", "k1", "a");
        conditions.require("m", "k2", "b");
        conditions.setProperty("k1", "a");
        assertFalse(conditions.eligible("m"), "多条件 AND 部分满足即否");
        assertEquals(1, conditions.unmet("m").size(), "未满足条件留痕");
        conditions.setProperty("k2", "b");
        assertTrue(conditions.eligible("m"), "全条件满足");
        conditions.require("solo", "switch", "on");
        assertFalse(conditions.eligible("solo"), "属性开关未开不满足");
        assertThrows(IllegalArgumentException.class, () -> conditions.require("x", " ", "v"), "空属性键拒绝");

        IocPort port = IocPort.inMemory();
        port.requireProperty("svc", "feature.x", "on");
        port.register("svc", "S", "singleton");
        assertTrue(port.skipped("svc"), "谓词不匹配跳过注册留痕");
        assertThrows(IllegalStateException.class, () -> port.get("svc"), "条件不满足获取拒绝");
        port.setProperty("feature.x", "on");
        port.register("svc", "S", "singleton");
        assertFalse(port.skipped("svc"), "开关打开后注册成功");
        assertNotNull(port.get("svc"));
    }

    @Test
    void portPipeline() {
        IocPort port = IocPort.inMemory();
        port.register("repo", "Repo", "singleton");
        port.register("svc", "Svc", "singleton");
        port.alias("svc", "service");
        port.dependsOn("svc", "repo");
        port.listener("audit", "svc.ready");
        assertEquals("svc", port.get("service").beanName(), "别名经端口获取");
        assertEquals(List.of("audit"), port.publish("svc.ready"));
        assertEquals(List.of("type", "labels", "attrs"), port.blocksShape(), "desiredkernel Block 形状只读联动");
        port.close();
        assertThrows(IllegalStateException.class, () -> port.publish("svc.ready"), "关闭后发布拒绝");
    }
}
