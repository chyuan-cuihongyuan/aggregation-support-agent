package cn.chyuan.ai.domain.rediskernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 过期策略（工单 0650 BY4，redis 思想）。
 * 毫秒 TTL/惰性删除（访问时判定并摘除）/定期抽样删除（顺序扫描样本上限，
 * 过比例由调用方续跑）/过期键对查询不可见/持久键不受影响。
 */
public final class Expires {

    /** 键→过期时刻（epoch ms），插入序稳定便于确定性抽样 */
    private final Map<String, Long> expireAt = new LinkedHashMap<>();

    public void set(String key, long atMs) {
        if (key == null) {
            throw new IllegalArgumentException("过期键不得为 null");
        }
        if (atMs <= 0) {
            throw new IllegalArgumentException("过期时刻必须为正");
        }
        expireAt.put(key, atMs);
    }

    public void clear(String key) {
        if (key == null) {
            throw new IllegalArgumentException("过期键不得为 null");
        }
        expireAt.remove(key);
    }

    /** 全量清除（快照整库载入前） */
    public void clearAll() {
        expireAt.clear();
    }

    public Long at(String key) {
        return expireAt.get(key);
    }

    public boolean isExpired(String key, long nowMs) {
        Long at = expireAt.get(key);
        return at != null && nowMs >= at;
    }

    /** 惰性判定：过期即摘除返回 true */
    public boolean lazyExpire(String key, long nowMs) {
        if (isExpired(key, nowMs)) {
            expireAt.remove(key);
            return true;
        }
        return false;
    }

    /**
     * 定期抽样一轮：按插入序扫描至多 samples 个键，
     * 摘除其中已过期者并返回清单；删除数超过 samples/4 时调用方应续跑
     * （redis activeExpireCycle 的过时比例延续语义）。
     */
    public List<String> sampleExpired(long nowMs, int samples) {
        if (samples <= 0) {
            throw new IllegalArgumentException("抽样样本数必须为正");
        }
        List<String> expired = new ArrayList<>();
        int scanned = 0;
        for (Map.Entry<String, Long> e : expireAt.entrySet()) {
            if (scanned >= samples) {
                break;
            }
            scanned++;
            if (nowMs >= e.getValue()) {
                expired.add(e.getKey());
            }
        }
        for (String key : expired) {
            expireAt.remove(key);
        }
        return expired;
    }

    public int size() {
        return expireAt.size();
    }
}
