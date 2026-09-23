package cn.chyuan.ai.domain.rediskernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 淘汰策略（工单 0651 BY5，redis 思想）。
 * noeviction/allkeys-lru/volatile-lru/allkeys-lfu 策略可配/
 * 采样池近似淘汰（池深 16，随机采样取最冷）/LFU 对数计数器+时间衰减/
 * 内存超限时 noeviction 拒绝写（admits=false）。
 */
public final class Eviction {

    public enum Policy { NO_EVICTION, ALLKEYS_LRU, VOLATILE_LRU, ALLKEYS_LFU }

    public static final int POOL_SIZE = 16;
    public static final int LFU_INIT_VAL = 5;

    /** 键元数据：LRU 时钟/LFU 对数计数/字节成本/是否带 TTL */
    public static final class Meta {
        long lruClock;
        byte lfu = LFU_INIT_VAL;
        long lastDecayMs;
        long sizeBytes;
        boolean volatileKey;
    }

    private final Map<String, Meta> metas = new LinkedHashMap<>();
    private final Random random;
    private Policy policy = Policy.NO_EVICTION;
    /** 0 = 不设上限 */
    private long maxMemoryBytes;
    private long usedBytes;
    private final String[] poolKeys = new String[POOL_SIZE];
    private final long[] poolIdle = new long[POOL_SIZE];
    private int poolCount;
    private String lastEvicted;
    private int evictionCount;

    public Eviction(Random random) {
        if (random == null) {
            throw new IllegalArgumentException("随机源不得为 null");
        }
        this.random = random;
    }

    public void setPolicy(Policy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("淘汰策略不得为 null");
        }
        this.policy = policy;
    }

    public Policy policy() {
        return policy;
    }

    public void setMaxMemory(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("内存上限不得为负");
        }
        this.maxMemoryBytes = bytes;
    }

    public long maxMemory() {
        return maxMemoryBytes;
    }

    public long usedBytes() {
        return usedBytes;
    }

    public int evictionCount() {
        return evictionCount;
    }

    public String lastEvicted() {
        return lastEvicted;
    }

    public Meta meta(String key) {
        return metas.get(key);
    }

    public void register(String key, long sizeBytes, long nowMs, boolean volatileKey) {
        if (key == null) {
            throw new IllegalArgumentException("淘汰登记键不得为 null");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("键字节成本不得为负");
        }
        Meta meta = new Meta();
        meta.lruClock = nowMs;
        meta.lastDecayMs = nowMs;
        meta.sizeBytes = sizeBytes;
        meta.volatileKey = volatileKey;
        Meta prev = metas.put(key, meta);
        if (prev != null) {
            usedBytes -= prev.sizeBytes;
        }
        usedBytes += sizeBytes;
    }

    public void unregister(String key) {
        Meta meta = metas.remove(key);
        if (meta != null) {
            usedBytes -= meta.sizeBytes;
        }
    }

    public void setVolatile(String key, boolean volatileKey) {
        Meta meta = metas.get(key);
        if (meta != null) {
            meta.volatileKey = volatileKey;
        }
    }

    /** 访问：刷新 LRU 时钟；LFU 策略下先衰减再对数递增计数器 */
    public void touch(String key, long nowMs) {
        Meta meta = metas.get(key);
        if (meta == null) {
            return;
        }
        decayLfu(meta, nowMs);
        meta.lruClock = nowMs;
        if (policy == Policy.ALLKEYS_LFU) {
            meta.lfu = lfuLogIncr(meta.lfu, random);
        }
    }

    /** 是否可写入 incoming 字节（超限且 noeviction → false，由调用方拒绝写） */
    public boolean admits(long incomingBytes) {
        if (maxMemoryBytes <= 0) {
            return true;
        }
        return usedBytes + incomingBytes <= maxMemoryBytes;
    }

    /**
     * 采样池近似淘汰：按策略筛选候选键（volatile 只采带 TTL 者），
     * 随机采样至多池深个，取最冷（LRU 最久未访问/LFU 计数最小）者逐出，
     * 注销其字节并返回键名；候选为空返回 null。调用方负责从存储移除该键。
     */
    public String evictOne(long nowMs) {
        List<String> candidates = new ArrayList<>();
        for (Map.Entry<String, Meta> e : metas.entrySet()) {
            if (policy != Policy.VOLATILE_LRU || e.getValue().volatileKey) {
                candidates.add(e.getKey());
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        poolCount = 0;
        int samples = Math.min(POOL_SIZE, candidates.size());
        String[] arr = candidates.toArray(new String[0]);
        for (int i = 0; i < samples; i++) {
            int pick = i + random.nextInt(arr.length - i);
            String tmp = arr[i];
            arr[i] = arr[pick];
            arr[pick] = tmp;
            Meta meta = metas.get(arr[i]);
            decayLfu(meta, nowMs);
            long coldness = (policy == Policy.ALLKEYS_LFU)
                    ? 255L - meta.lfu
                    : Math.max(0L, nowMs - meta.lruClock);
            poolInsert(arr[i], coldness);
        }
        String victim = poolKeys[0];
        unregister(victim);
        lastEvicted = victim;
        evictionCount++;
        return victim;
    }

    /** 池插入：按冷度降序保留最冷 POOL_SIZE 个（poolIdle[0] 恒为最冷） */
    private void poolInsert(String key, long coldness) {
        int pos = poolCount;
        while (pos > 0 && poolIdle[pos - 1] < coldness) {
            if (pos < POOL_SIZE) {
                poolKeys[pos] = poolKeys[pos - 1];
                poolIdle[pos] = poolIdle[pos - 1];
            }
            pos--;
        }
        if (pos < POOL_SIZE) {
            poolKeys[pos] = key;
            poolIdle[pos] = coldness;
            if (poolCount < POOL_SIZE) {
                poolCount++;
            }
        }
    }

    private void decayLfu(Meta meta, long nowMs) {
        long elapsedMinutes = (nowMs - meta.lastDecayMs) / 60_000L;
        if (elapsedMinutes > 0) {
            meta.lfu = (byte) Math.max(LFU_INIT_VAL, meta.lfu - elapsedMinutes);
            meta.lastDecayMs = nowMs;
        }
    }

    /** LFU 对数递增：低于初值直接加一；否则以 1/(base+1) 概率加一（redis 公式，8 位无符号） */
    static byte lfuLogIncr(byte current, Random random) {
        int cur = current & 0xFF;
        int base = cur - LFU_INIT_VAL;
        int next;
        if (base < 0) {
            next = cur + 1;
        } else {
            double p = 1.0d / (base + 1.0d);
            next = random.nextDouble() < p ? cur + 1 : cur;
        }
        return (byte) Math.min(next, 255);
    }
}
