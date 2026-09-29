package cn.chyuan.ai.domain.modelkernel.service;

import java.util.HashMap;
import java.util.Map;

/**
 * 模型引用计数（工单 1035 EL7，ollama 引用生命周期思想）。
 * 引用归零才释放/仍有引用拒绝卸载/卸载清驻留（由端口编排）。
 */
public final class ModelRefs {

    private final Map<String, Long> counts = new HashMap<>();

    /** 取用引用 */
    public synchronized void acquire(String model) {
        counts.merge(model, 1L, Long::sum);
    }

    /** 显式卸载：引用归零才释放；仍有引用拒绝 */
    public synchronized boolean unload(String model) {
        long count = counts.getOrDefault(model, 0L);
        if (count < 0) {
            throw new IllegalStateException("引用计数为负: " + model);
        }
        if (count > 0) {
            throw new IllegalStateException("仍有引用，卸载拒绝: " + model + "=" + count);
        }
        return true;
    }

    /** 归零确认后清除计数（卸载完成收尾） */
    public synchronized void cleared(String model) {
        counts.remove(model);
    }

    public synchronized long count(String model) {
        return counts.getOrDefault(model, 0L);
    }
}
