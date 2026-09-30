package cn.chyuan.ai.domain.iockernel.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 生命周期钩子（工单 1123 EV5，spring-framework 思想）。
 * 初始化钩子 afterPropertiesSet 按完成序留痕；销毁钩子 destroy 单个回收；
 * close 逆序销毁全部未销毁单例；原型不进初始化台账故不托管销毁。
 */
public final class LifecycleHooks {

    private final List<String> initialized = new ArrayList<>();
    private final Set<String> destroyed = new LinkedHashSet<>();
    private boolean closed;

    /** 初始化完成留痕：重复初始化拒绝 */
    public void markInitialized(String beanName) {
        if (beanName == null || beanName.isBlank()) {
            throw new IllegalArgumentException("bean 名不能为空");
        }
        if (initialized.contains(beanName)) {
            throw new IllegalStateException("重复初始化: " + beanName);
        }
        initialized.add(beanName);
    }

    /** 初始化完成序（只读） */
    public List<String> initOrder() {
        return List.copyOf(initialized);
    }

    /** 单个销毁：未初始化或已销毁拒绝 */
    public void destroy(String beanName) {
        if (!initialized.contains(beanName)) {
            throw new IllegalArgumentException("未初始化不可销毁: " + beanName);
        }
        if (destroyed.contains(beanName)) {
            throw new IllegalStateException("重复销毁: " + beanName);
        }
        destroyed.add(beanName);
    }

    /** 容器关闭：逆序销毁全部未销毁单例，返回销毁序；重复关闭拒绝 */
    public List<String> close() {
        if (closed) {
            throw new IllegalStateException("容器已关闭");
        }
        closed = true;
        List<String> reverse = new ArrayList<>(initialized);
        java.util.Collections.reverse(reverse);
        List<String> destroyedNow = new ArrayList<>();
        for (String beanName : reverse) {
            if (!destroyed.contains(beanName)) {
                destroyed.add(beanName);
                destroyedNow.add(beanName);
            }
        }
        return destroyedNow;
    }

    public boolean isClosed() {
        return closed;
    }

    public boolean isDestroyed(String beanName) {
        return destroyed.contains(beanName);
    }
}
