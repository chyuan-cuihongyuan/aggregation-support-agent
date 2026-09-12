package cn.chyuan.ai.domain.rag.service.profile;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 检索参数画像采集与统计纯内核（工单 0235 AE8，借鉴 Qdrant 参数治理）—
 * 配置组合键（topK/efSearch/权重）→ 环形缓冲样本（命中数/延迟毫秒）→
 * 聚合统计（次数/命中率均值/延迟均值/P95）。写入经 store 端口落 retrieval_profile
 * 第 15 表（双方言），本类为内存聚合内核。
 *
 * @author chyuan
 */
public class RetrievalProfileCollector {

    /** 配置组合键 */
    public record ProfileKey(int topK, int efSearch, double vectorRatio) {
        public ProfileKey {
            topK = Math.max(1, topK);
            efSearch = Math.max(0, efSearch);
            vectorRatio = Math.min(1, Math.max(0, vectorRatio));
        }

        public String key() {
            return "topK=" + topK + ",ef=" + efSearch + ",vr=" + vectorRatio;
        }
    }

    /** 单次检索样本 */
    public record Sample(int hitCount, int topK, long latencyMs) {
    }

    /** 聚合统计 */
    public record ProfileStats(String key, long samples, double avgHitRate, double avgLatencyMs,
            long p95LatencyMs) {

        public Map<String, Object> toMap() {
            return Map.of("key", key, "samples", samples, "avgHitRate", avgHitRate,
                    "avgLatencyMs", avgLatencyMs, "p95LatencyMs", p95LatencyMs);
        }
    }

    /** 每配置环形样本缓冲（默认 200） */
    static final int RING_SIZE = 200;

    private final Map<String, java.util.ArrayDeque<Sample>> rings = new ConcurrentHashMap<>();

    /** 记录一次检索样本 */
    public void record(ProfileKey key, Sample sample) {
        if (key == null || sample == null) {
            return;
        }
        rings.computeIfAbsent(key.key(), k -> new java.util.ArrayDeque<>())
                .addLast(sample);
        java.util.ArrayDeque<Sample> ring = rings.get(key.key());
        synchronized (ring) {
            while (ring.size() > RING_SIZE) {
                ring.removeFirst();
            }
        }
    }

    /** 单配置聚合统计（P95 = 延迟升序第 ⌈0.95n⌉ 位） */
    public ProfileStats stats(ProfileKey key) {
        java.util.ArrayDeque<Sample> ring = rings.get(key.key());
        if (ring == null || ring.isEmpty()) {
            return new ProfileStats(key.key(), 0, 0, 0, 0);
        }
        List<Sample> samples;
        synchronized (ring) {
            samples = List.copyOf(ring);
        }
        AtomicLong hits = new AtomicLong();
        AtomicLong latency = new AtomicLong();
        samples.forEach(s -> {
            hits.addAndGet(s.hitCount());
            latency.addAndGet(s.latencyMs());
        });
        double avgHitRate = (double) hits.get() / samples.size() / Math.max(1, key.topK());
        List<Long> latencies = samples.stream().map(Sample::latencyMs).sorted().toList();
        int p95Index = (int) Math.ceil(0.95 * latencies.size()) - 1;
        return new ProfileStats(key.key(), samples.size(), avgHitRate,
                (double) latency.get() / samples.size(), latencies.get(Math.max(0, p95Index)));
    }

    /** 全配置统计（键序稳定） */
    public List<ProfileStats> allStats() {
        return rings.keySet().stream().sorted()
                .map(k -> stats(parseKey(k)))
                .toList();
    }

    static ProfileKey parseKey(String key) {
        int topK = 0;
        int ef = 0;
        double vr = 0;
        for (String part : key.split(",")) {
            String[] kv = part.split("=");
            if (kv.length != 2) {
                continue;
            }
            switch (kv[0]) {
                case "topK" -> topK = Integer.parseInt(kv[1]);
                case "ef" -> ef = Integer.parseInt(kv[1]);
                case "vr" -> vr = Double.parseDouble(kv[1]);
                default -> {
                }
            }
        }
        return new ProfileKey(topK, ef, vr);
    }
}
