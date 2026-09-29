package cn.chyuan.ai.domain.modelkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模型清单内核测试（工单 1029-1036 EL1-EL8，ollama 思想）。
 * manifest 解析/digest 校验/层存储/tag 注册/加载队列/keep-alive/显式卸载/端口组合管线。
 */
class ModelKernelTest {

    private static final String D1 =
            "sha256:a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";

    @Test
    void manifestParse() {
        Manifest manifest = Manifest.parse(List.of(
                Map.of("mediaType", "application/vnd.ollama.image.model",
                        "digest", D1, "size", 100),
                Map.of("mediaType", "application/vnd.ollama.image.params",
                        "digest", D1.substring(0, 10) + "b".repeat(54), "size", 20)));
        assertEquals(2, manifest.layerCount());
        assertEquals(120, manifest.totalSize(), "分层大小合计");
        assertEquals(D1.substring(0, 10) + "b".repeat(54), manifest.layers().get(1).digest(),
                "层 digest 原样保留");

        assertThrows(IllegalArgumentException.class, () -> Manifest.parse(List.of()), "空层拒绝");
        assertThrows(IllegalArgumentException.class, () -> Manifest.parse(null), "空层拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> Manifest.parse(List.of(Map.of("digest", D1, "size", 1))), "缺 mediaType 拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> Manifest.parse(List.of(Map.of("mediaType", "m", "size", 1))), "缺 digest 拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> Manifest.parse(List.of(Map.of("mediaType", "m", "digest", D1))), "缺 size 拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> Manifest.parse(List.of(Map.of("mediaType", "m", "digest", D1, "size", -1))), "负 size 拒绝");
    }

    @Test
    void digestValidate() {
        Digests.validate(D1);
        assertTrue(Digests.isValid(D1));
        assertFalse(Digests.isValid("sha256:zz"), "非法字符无效");
        assertThrows(IllegalArgumentException.class, () -> Digests.validate(null), "空拒绝");
        assertThrows(IllegalArgumentException.class, () -> Digests.validate(""), "空拒绝");
        assertThrows(IllegalArgumentException.class, () -> Digests.validate("md5:abc"), "缺前缀拒绝");
        assertThrows(IllegalArgumentException.class, () -> Digests.validate("sha256:abc"), "长度不符拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> Digests.validate("sha256:" + "A".repeat(64)), "大写非法拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> Digests.validate("sha256:" + "g".repeat(64)), "非法字符拒绝");
    }

    @Test
    void layerStoreRefcount() {
        LayerStore store = new LayerStore();
        store.put(D1, "model-bytes");
        store.put(D1, "model-bytes");
        assertTrue(store.has(D1), "重复存入幂等");
        assertEquals(1, store.size());
        assertThrows(IllegalStateException.class, () -> store.put(D1, "other-bytes"), "同 digest 异内容冲突拒绝");

        assertThrows(IllegalStateException.class, () -> store.ref("sha256:" + "c".repeat(64)), "引用未存内容拒绝");
        store.ref(D1);
        store.ref(D1);
        assertEquals(2, store.refs(D1));
        assertFalse(store.unref(D1), "未归零不删除");
        assertTrue(store.unref(D1), "归零释放删除内容");
        assertFalse(store.has(D1));
        assertThrows(IllegalStateException.class, () -> store.unref(D1), "重复解引用拒绝");
        assertThrows(IllegalArgumentException.class, () -> store.put("bad", "x"), "非法 digest 拒绝");
    }

    @Test
    void modelRegistryTags() {
        ModelRegistry registry = new ModelRegistry();
        Manifest manifest = Manifest.parse(List.of(Map.of("mediaType", "m", "digest", D1, "size", 10)));
        registry.register("llama3:8b", manifest);
        registry.register("llama3:8b", manifest);
        assertEquals(1, registry.tags().size(), "重复 tag 覆盖");
        Manifest other = Manifest.parse(List.of(Map.of("mediaType", "m", "digest", D1, "size", 999)));
        registry.register("qwen:7b", other);
        assertEquals(2, registry.tags().size());
        assertEquals(List.of("llama3:8b", "qwen:7b"), registry.tags(), "tag 有序");
        assertSame(manifest, registry.resolve("llama3:8b"));
        assertThrows(IllegalArgumentException.class, () -> registry.resolve("ghost:1b"), "未注册 tag 拒绝");
        assertTrue(registry.unregister("qwen:7b"));
        assertThrows(IllegalArgumentException.class, () -> registry.unregister("qwen:7b"), "重复注销拒绝");
        assertThrows(IllegalArgumentException.class, () -> registry.register("", manifest), "空 tag 拒绝");
    }

    @Test
    void loadQueueFifo() {
        LoadQueue queue = new LoadQueue(2);
        queue.enqueue("llama3:8b", "sess-1");
        queue.enqueue("qwen:7b", "sess-2");
        assertEquals(2, queue.pending());
        assertThrows(IllegalStateException.class, () -> queue.enqueue("gemma:2b", "sess-3"), "容量超限拒绝");
        assertEquals("llama3:8b", queue.complete().model(), "FIFO 出队");
        assertEquals("qwen:7b", queue.complete().model());
        assertNull(queue.complete(), "空队列返回 null");
        assertEquals(0, queue.pending());
        assertThrows(IllegalArgumentException.class, () -> new LoadQueue(0), "零容量拒绝");
    }

    @Test
    void keepAliveExpiry() {
        KeepAlive keepAlive = new KeepAlive();
        keepAlive.load("llama3:8b", 3);
        assertEquals(3, keepAlive.remaining("llama3:8b"));
        keepAlive.renew("llama3:8b", 2);
        assertEquals(3, keepAlive.remaining("llama3:8b"), "续期取 max(剩余，新值)");
        keepAlive.renew("llama3:8b", 5);
        assertEquals(5, keepAlive.remaining("llama3:8b"), "更高新值生效");

        keepAlive.load("qwen:7b", 1);
        assertEquals(List.of("qwen:7b"), keepAlive.tick(), "到期卸载名单");
        assertEquals(4, keepAlive.remaining("llama3:8b"));
        assertEquals(0, keepAlive.remaining("qwen:7b"));
        assertEquals(List.of(), keepAlive.tick());
        keepAlive.clear("llama3:8b");
        assertEquals(0, keepAlive.resident(), "显式清除");
        assertThrows(IllegalArgumentException.class, () -> keepAlive.load("m", 0), "零驻留拒绝");
        assertThrows(IllegalArgumentException.class, () -> keepAlive.renew("m", -1), "负续期拒绝");
    }

    @Test
    void modelRefsUnload() {
        ModelRefs refs = new ModelRefs();
        refs.acquire("llama3:8b");
        assertEquals(1, refs.count("llama3:8b"));
        assertThrows(IllegalStateException.class, () -> refs.unload("llama3:8b"), "仍有引用卸载拒绝");
        refs.acquire("llama3:8b");
        assertEquals(2, refs.count("llama3:8b"));
        assertTrue(refs.unload("ghost"), "引用归零可释放确认");
        refs.cleared("ghost");
        assertEquals(0, refs.count("ghost"));
    }

    @Test
    void modelPortPipeline() {
        ModelPort port = ModelPort.inMemory();
        List<Map<String, Object>> layers = List.of(
                Map.of("mediaType", "application/vnd.ollama.image.model", "digest", D1, "size", 100));
        assertEquals(100, port.pull("llama3:8b", layers), "pull 返回总大小");
        assertEquals(List.of("llama3:8b"), port.tags());
        assertThrows(IllegalArgumentException.class,
                () -> port.pull("bad", List.of(Map.of("digest", D1, "size", 1))), "缺字段拒绝");

        LoadQueue.Request request = port.enqueueLoad("llama3:8b", "sess-1");
        assertEquals("llama3:8b", request.model());
        assertThrows(IllegalArgumentException.class,
                () -> port.enqueueLoad("ghost:1b", "sess-2"), "未注册 tag 加载拒绝");
        assertEquals(request, port.completeLoad());

        port.loaded("llama3:8b", 2);
        assertEquals(1, port.refs("llama3:8b"));
        port.renew("llama3:8b", 5);
        assertEquals(List.of(), port.tick(), "续期后未到期");
        assertEquals(List.of(), port.tick());
        assertEquals(List.of(), port.tick());
        assertEquals(List.of(), port.tick());
        assertEquals(List.of("llama3:8b"), port.tick(), "到期自动卸载");
        assertEquals(1, port.refs("llama3:8b"), "到期卸载不改引用计数");

        assertThrows(IllegalStateException.class, () -> port.unload("llama3:8b"), "仍有引用卸载拒绝");
        port.setDefaultOption("temperature", "0.8");
        port.setDefaultOption("top_p", "0.9");
        assertEquals("temperature=0.8;top_p=0.9", port.optionsShape(), "inferkernel 参数形状联动");
        assertThrows(IllegalArgumentException.class, () -> port.setDefaultOption("", "x"), "空参数键拒绝");
    }
}
