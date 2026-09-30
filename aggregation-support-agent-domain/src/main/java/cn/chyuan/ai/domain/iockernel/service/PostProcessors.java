package cn.chyuan.ai.domain.iockernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * BeanPostProcessor 链（工单 1122 EV4，spring-framework 思想）。
 * 初始化前后回调按注册序执行，处理器可替换实例（返回新 Instance 即替换）；
 * 原型同样参与回调但不进缓存。
 */
public final class PostProcessors {

    /** 后处理器：before 在初始化钩子前、after 在初始化钩子后；返回替换实例 */
    public interface Processor {
        Instance before(String beanName, Instance bean);

        Instance after(String beanName, Instance bean);
    }

    private final List<Processor> processors = new ArrayList<>();
    private final List<String> invoked = new ArrayList<>();

    public void register(Processor processor) {
        if (processor == null) {
            throw new IllegalArgumentException("后处理器不能为空");
        }
        processors.add(processor);
    }

    public int count() {
        return processors.size();
    }

    /** 初始化前折叠：按注册序穿透，任一处理器可替换实例 */
    public Instance before(String beanName, Instance bean) {
        Instance current = bean;
        for (Processor processor : processors) {
            Instance replaced = processor.before(beanName, current);
            invoked.add("before:" + beanName);
            if (replaced != null) {
                current = replaced;
            }
        }
        return current;
    }

    /** 初始化后折叠：同注册序 */
    public Instance after(String beanName, Instance bean) {
        Instance current = bean;
        for (Processor processor : processors) {
            Instance replaced = processor.after(beanName, current);
            invoked.add("after:" + beanName);
            if (replaced != null) {
                current = replaced;
            }
        }
        return current;
    }

    /** 回调轨迹（注册/调用序，只读） */
    public List<String> trace() {
        return List.copyOf(invoked);
    }
}
