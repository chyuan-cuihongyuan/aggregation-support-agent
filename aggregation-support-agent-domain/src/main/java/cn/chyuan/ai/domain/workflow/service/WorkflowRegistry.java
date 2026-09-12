package cn.chyuan.ai.domain.workflow.service;

import cn.chyuan.ai.domain.workflow.model.WorkflowGraph;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作流注册表 + 租户灰度切流（工单 0211 AB8）—
 * 同名多版本注册（名称+版本唯一）；TenantRouter 按租户稳定哈希百分比选版：
 * sha256(name#tenantId) 前 8 字节 → int → %100 < percentage 则选 canary 版本，否则默认
 * （最高版本）。同租户多次选版结果一致（stickiness）；未配置租户走默认。
 * 语义为发布路由（Unleash stickiness 思想），非 AB 实验（0194 D4 出界线不触碰）。
 *
 * @author chyuan
 */
public class WorkflowRegistry {

    /** 版本记录 */
    public record VersionedGraph(String name, int version, WorkflowGraph graph, int canaryPercentage) {
    }

    private final Map<String, Map<Integer, VersionedGraph>> registry = new ConcurrentHashMap<>();

    /** 注册版本（同名同版本重复注册抛错） */
    public void register(WorkflowGraph graph, int version, int canaryPercentage) {
        registry.computeIfAbsent(graph.name(), k -> new ConcurrentHashMap<>())
                .compute(version, (v, existing) -> {
                    if (existing != null) {
                        throw new IllegalArgumentException("版本已注册: " + graph.name() + " v" + version);
                    }
                    return new VersionedGraph(graph.name(), version, graph, canaryPercentage);
                });
    }

    /** 取指定版本（无则 null） */
    public VersionedGraph get(String name, int version) {
        Map<Integer, VersionedGraph> versions = registry.get(name);
        return versions == null ? null : versions.get(version);
    }

    /** 版本列表（升序） */
    public List<VersionedGraph> versions(String name) {
        Map<Integer, VersionedGraph> versions = registry.get(name);
        if (versions == null) {
            return List.of();
        }
        List<VersionedGraph> out = new ArrayList<>(versions.values());
        out.sort(Comparator.comparingInt(VersionedGraph::version));
        return out;
    }

    /** 租户路由选版：切流百分比命中 canary（最高版本），否则默认（最高版本；仅一个版本时同一版本） */
    public VersionedGraph route(String name, String tenantId) {
        List<VersionedGraph> versions = versions(name);
        if (versions.isEmpty()) {
            return null;
        }
        VersionedGraph latest = versions.get(versions.size() - 1);
        // 只有一个版本，或最高版本未配置切流 → 默认版本
        if (versions.size() == 1) {
            return latest;
        }
        // canary = 最高版本；percentage 以 canary 版本配置为准
        int percentage = Math.max(0, Math.min(100, latest.canaryPercentage()));
        if (percentage <= 0) {
            return versions.get(versions.size() - 2);
        }
        if (tenantId == null || tenantId.isBlank()) {
            return versions.get(versions.size() - 2);
        }
        return stableBucket(name, tenantId) < percentage ? latest : versions.get(versions.size() - 2);
    }

    /** 稳定桶：sha256(name#tenantId) → [0,100) */
    static int stableBucket(String name, String tenantId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((name + "#" + tenantId).getBytes(StandardCharsets.UTF_8));
            int value = ((digest[0] & 0xFF) << 24) | ((digest[1] & 0xFF) << 16)
                    | ((digest[2] & 0xFF) << 8) | (digest[3] & 0xFF);
            return Math.floorMod(value, 100);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
