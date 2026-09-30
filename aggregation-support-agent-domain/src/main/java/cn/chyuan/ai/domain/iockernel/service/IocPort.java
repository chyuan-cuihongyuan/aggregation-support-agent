package cn.chyuan.ai.domain.iockernel.service;

import java.util.List;

/**
 * IoC 容器端口（工单 1126 EV8，spring-framework 思想）。
 * register·get·publish·close 入口统一编排：Bean 定义注册·依赖注入·三级缓存循环依赖·
 * BeanPostProcessor·生命周期·事件广播·条件装配组合管线/desiredkernel 期望资源形状只读联动
 * （形状键与 desiredkernel ConfigModel.Block 字段对齐，不 import desiredkernel）/
 * ioc-kernel.enabled 默认关（开启才改变行为）。
 */
public interface IocPort {

    /** 注册 Bean 定义；条件不满足时跳过并留痕（EV1/EV7） */
    void register(String name, String className, String scope);

    /** 条件不满足被跳过的 bean 名（EV7） */
    boolean skipped(String name);

    /** 挂条件（属性键=期望值，多条件 AND）——须在 register 前调用（EV7） */
    void requireProperty(String beanName, String property, String expectedValue);

    /** 设置属性开关（EV7） */
    void setProperty(String key, String value);

    /** 注册别名（EV1） */
    void alias(String beanName, String alias);

    /** 声明依赖边：自引用/未知 bean 拒绝（EV2/EV3） */
    void dependsOn(String beanName, String dependency);

    /** 注册 BeanPostProcessor（EV4） */
    void addPostProcessor(PostProcessors.Processor processor);

    /** 注册事件监听器（EV6） */
    void listener(String listenerName, String eventType);

    /** 获取 Bean：单例走三级缓存，原型每次新实例（EV2/EV3） */
    Instance get(String name);

    /** 发布事件，返回到达监听器序（EV6） */
    List<String> publish(String eventType);

    /** 关闭容器：逆序销毁单例，返回销毁序（EV5） */
    List<String> close();

    /** desiredkernel 期望资源形状只读联动（ConfigModel.Block: type/labels/attrs） */
    List<String> blocksShape();

    static IocPort inMemory() {
        return new IocContainer();
    }
}
