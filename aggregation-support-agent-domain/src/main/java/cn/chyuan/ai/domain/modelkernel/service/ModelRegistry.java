package cn.chyuan.ai.domain.modelkernel.service;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 模型注册（工单 1032 EL4，ollama tag 思想）。
 * tag 别名指向 manifest/重复 tag 覆盖/未注册 tag 拒绝。
 */
public final class ModelRegistry {

    private final Map<String, Manifest> byTag = new TreeMap<>();

    /** 注册别名：重复 tag 覆盖 */
    public synchronized void register(String tag, Manifest manifest) {
        if (tag == null || tag.isEmpty()) {
            throw new IllegalArgumentException("tag 为空");
        }
        byTag.put(tag, manifest);
    }

    public synchronized Manifest resolve(String tag) {
        Manifest manifest = byTag.get(tag);
        if (manifest == null) {
            throw new IllegalArgumentException("未注册 tag: " + tag);
        }
        return manifest;
    }

    public synchronized boolean has(String tag) {
        return byTag.containsKey(tag);
    }

    public synchronized List<String> tags() {
        return List.copyOf(byTag.keySet());
    }

    /** 注销：存在返回 true；未注册拒绝 */
    public synchronized boolean unregister(String tag) {
        if (byTag.remove(tag) == null) {
            throw new IllegalArgumentException("未注册 tag: " + tag);
        }
        return true;
    }
}
