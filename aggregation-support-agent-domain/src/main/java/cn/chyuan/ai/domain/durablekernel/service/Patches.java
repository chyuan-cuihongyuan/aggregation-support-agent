package cn.chyuan.ai.domain.durablekernel.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 版本补丁（工单 1069 EP4，temporal 思想）。
 * patch 标记注册/带标记新代码走新路径/无标记（含毕业移除后）走旧路径重放旧历史/
 * 毕业未知标记拒绝；注册重复幂等。
 */
public final class Patches {

    private final Set<String> markers = ConcurrentHashMap.newKeySet();

    /** 注册补丁标记（重复幂等） */
    public void register(String patchId) {
        requirePatchId(patchId);
        markers.add(patchId);
    }

    /** 是否带标记：true 走新路径，false 走旧路径 */
    public boolean has(String patchId) {
        requirePatchId(patchId);
        return markers.contains(patchId);
    }

    /** 毕业移除标记：此后新旧历史统一走旧（新基线）路径 */
    public void graduate(String patchId) {
        requirePatchId(patchId);
        if (!markers.remove(patchId)) {
            throw new IllegalArgumentException("未知补丁标记拒绝毕业: " + patchId);
        }
    }

    public int size() {
        return markers.size();
    }

    private void requirePatchId(String patchId) {
        if (patchId == null || patchId.isBlank()) {
            throw new IllegalArgumentException("patchId 不能为空");
        }
    }
}
