package cn.chyuan.ai.domain.modelkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 模型清单编排实现（工单 1036 EL8，ollama 思想）。
 * 组合 manifest 解析/digest 校验/层存储/注册表/加载队列/keep-alive/引用计数；
 * inferkernel 运行参数形状只读联动：默认参数键值串（形状数据不 import inferkernel）。
 */
public final class ModelHub implements ModelPort {

    private final LayerStore store = new LayerStore();
    private final ModelRegistry registry = new ModelRegistry();
    private final LoadQueue queue = new LoadQueue(8);
    private final KeepAlive keepAlive = new KeepAlive();
    private final ModelRefs refs = new ModelRefs();
    private final Map<String, String> defaultOptions = new TreeMap<>();

    @Override
    public long pull(String tag, List<Map<String, Object>> rawLayers) {
        Manifest manifest = Manifest.parse(rawLayers);
        for (Manifest.Layer layer : manifest.layers()) {
            store.put(layer.digest(), "blob:" + layer.digest());
        }
        for (Manifest.Layer layer : manifest.layers()) {
            store.ref(layer.digest());
        }
        registry.register(tag, manifest);
        return manifest.totalSize();
    }

    @Override
    public List<String> tags() {
        return registry.tags();
    }

    @Override
    public LoadQueue.Request enqueueLoad(String tag, String requester) {
        registry.resolve(tag);
        return queue.enqueue(tag, requester);
    }

    @Override
    public LoadQueue.Request completeLoad() {
        return queue.complete();
    }

    @Override
    public void loaded(String tag, long keepAliveTicks) {
        registry.resolve(tag);
        refs.acquire(tag);
        keepAlive.load(tag, keepAliveTicks);
    }

    @Override
    public void renew(String tag, long keepAliveTicks) {
        keepAlive.renew(tag, keepAliveTicks);
    }

    @Override
    public void unload(String tag) {
        registry.resolve(tag);
        refs.unload(tag);
        refs.cleared(tag);
        keepAlive.clear(tag);
    }

    @Override
    public int refs(String tag) {
        return (int) refs.count(tag);
    }

    @Override
    public List<String> tick() {
        return keepAlive.tick();
    }

    @Override
    public String optionsShape() {
        if (defaultOptions.isEmpty()) {
            return "";
        }
        List<String> pairs = new ArrayList<>();
        defaultOptions.forEach((k, v) -> pairs.add(k + "=" + v));
        return String.join(";", pairs);
    }

    @Override
    public void setDefaultOption(String key, String value) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("参数键为空");
        }
        defaultOptions.put(key, value);
    }
}
