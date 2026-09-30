package cn.chyuan.ai.domain.iockernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件广播器（工单 1124 EV6，spring-framework 思想）。
 * 监听器按注册序接收同类型事件；类型不匹配不收；单监听器异常隔离不影响后续；
 * 发布顺序即到达序。
 */
public final class EventMulticaster {

    /** 投递报告：delivered 为到达监听器序，failed 为本次异常监听器 */
    public record DeliveryReport(List<String> delivered, List<String> failed) {
    }

    /** 监听器：名称唯一 + 订阅事件类型 */
    private record Listener(String name, String eventType) {
    }

    private final Map<String, Listener> listeners = new LinkedHashMap<>();
    private final List<String> failNext = new ArrayList<>();

    /** 注册监听器：空名/空类型/重复名拒绝 */
    public void register(String listenerName, String eventType) {
        if (listenerName == null || listenerName.isBlank() || eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("监听器名与事件类型不能为空");
        }
        if (listeners.containsKey(listenerName)) {
            throw new IllegalArgumentException("重复监听器: " + listenerName);
        }
        listeners.put(listenerName, new Listener(listenerName, eventType));
    }

    /** 标记监听器下次投递时异常：未知监听器拒绝 */
    public void failNext(String listenerName) {
        if (!listeners.containsKey(listenerName)) {
            throw new IllegalArgumentException("未知监听器: " + listenerName);
        }
        failNext.add(listenerName);
    }

    /** 发布事件：按注册序过滤类型后投递，异常隔离继续 */
    public DeliveryReport publish(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("事件类型不能为空");
        }
        List<String> delivered = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (Listener listener : listeners.values()) {
            if (!listener.eventType().equals(eventType)) {
                continue;
            }
            if (failNext.remove(listener.name())) {
                failed.add(listener.name());
                continue;
            }
            delivered.add(listener.name());
        }
        return new DeliveryReport(List.copyOf(delivered), List.copyOf(failed));
    }

    public int listenerCount() {
        return listeners.size();
    }
}
