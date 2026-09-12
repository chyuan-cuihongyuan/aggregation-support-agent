package cn.chyuan.ai.domain.workflow.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作流执行上下文（工单 0205 AB2）— 键值载体，节点间传递；
 * snapshot() 输出保序不可变副本供检查点（AB3）留档。
 *
 * @author chyuan
 */
public class WorkflowContext {

    private final Map<String, Object> values = new LinkedHashMap<>();

    public void put(String key, Object value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("上下文键不能为空");
        }
        values.put(key, value);
    }

    public Object get(String key) {
        return values.get(key);
    }

    public String getString(String key) {
        Object value = values.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public boolean contains(String key) {
        return values.containsKey(key);
    }

    /** 不可变快照（浅拷贝；供检查点留档与回放） */
    public Map<String, Object> snapshot() {
        return Map.copyOf(values);
    }

    /** 从快照恢复（检查点续跑入口） */
    public static WorkflowContext fromSnapshot(Map<String, Object> snapshot) {
        WorkflowContext ctx = new WorkflowContext();
        if (snapshot != null) {
            ctx.values.putAll(snapshot);
        }
        return ctx;
    }
}
