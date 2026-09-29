package cn.chyuan.ai.domain.modelkernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * keep-alive 驻留（工单 1034 EL6，ollama keep_alive 思想）。
 * 驻留计时步进/到期卸载/续期取 max(剩余，新值)。
 */
public final class KeepAlive {

    private final Map<String, Long> remaining = new HashMap<>();

    /** 装载驻留：按 tick 计时 */
    public synchronized void load(String model, long ticks) {
        if (ticks <= 0) {
            throw new IllegalArgumentException("驻留时长必须为正: " + ticks);
        }
        remaining.put(model, ticks);
    }

    /** 续期：取 max(剩余，新值)，不缩短 */
    public synchronized void renew(String model, long ticks) {
        long current = remaining.getOrDefault(model, 0L);
        if (ticks <= 0) {
            throw new IllegalArgumentException("续期时长必须为正: " + ticks);
        }
        remaining.put(model, Math.max(current, ticks));
    }

    /** 步进：全部驻留 -1，返回到 0 的到期名单 */
    public synchronized List<String> tick() {
        List<String> expired = new ArrayList<>();
        remaining.forEach((model, ticks) -> {
            long left = ticks - 1;
            if (left <= 0) {
                expired.add(model);
            } else {
                remaining.put(model, left);
            }
        });
        expired.forEach(remaining::remove);
        return expired;
    }

    /** 立即清除驻留（显式卸载） */
    public synchronized void clear(String model) {
        remaining.remove(model);
    }

    public synchronized long remaining(String model) {
        return remaining.getOrDefault(model, 0L);
    }

    public synchronized int resident() {
        return remaining.size();
    }
}
