package cn.chyuan.ai.domain.modelkernel.service;

import java.util.HashMap;
import java.util.Map;

/**
 * 层存储（工单 1031 EL3，ollama blob 存储思想）。
 * 内容按 digest 寻址/引用计数/重复存入幂等/引用未存内容拒绝。
 */
public final class LayerStore {

    private final Map<String, String> blobs = new HashMap<>();
    private final Map<String, Long> refCounts = new HashMap<>();

    /** 存入内容：同 digest 同内容幂等；同 digest 异内容冲突拒绝；digest 格式校验 */
    public synchronized void put(String digest, String content) {
        Digests.validate(digest);
        if (content == null || content.isEmpty()) {
            throw new IllegalArgumentException("层内容为空");
        }
        String existing = blobs.get(digest);
        if (existing != null) {
            if (!existing.equals(content)) {
                throw new IllegalStateException("digest 冲突: " + digest);
            }
            return;
        }
        blobs.put(digest, content);
    }

    /** 引用：内容未存拒绝 */
    public synchronized void ref(String digest) {
        if (!blobs.containsKey(digest)) {
            throw new IllegalStateException("引用未存内容: " + digest);
        }
        refCounts.merge(digest, 1L, Long::sum);
    }

    /** 解引用：归零即删除内容；重复解引用拒绝 */
    public synchronized boolean unref(String digest) {
        long count = refCounts.getOrDefault(digest, 0L);
        if (count <= 0) {
            throw new IllegalStateException("解引用超计: " + digest);
        }
        if (count > 1) {
            refCounts.put(digest, count - 1);
            return false;
        }
        refCounts.remove(digest);
        blobs.remove(digest);
        return true;
    }

    public synchronized boolean has(String digest) {
        return blobs.containsKey(digest);
    }

    public synchronized String get(String digest) {
        String content = blobs.get(digest);
        if (content == null) {
            throw new IllegalStateException("内容不存在: " + digest);
        }
        return content;
    }

    public synchronized long refs(String digest) {
        return refCounts.getOrDefault(digest, 0L);
    }

    public synchronized int size() {
        return blobs.size();
    }
}
