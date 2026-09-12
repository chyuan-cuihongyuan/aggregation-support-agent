package cn.chyuan.ai.domain.workflow.service;

import cn.chyuan.ai.domain.workflow.model.WorkflowGraph;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注册表与租户切流单测（工单 0211 AB8）：版本注册/稳定哈希/边界/stickiness。
 */
class WorkflowRegistryTest {

    private static WorkflowGraph graph(String name) {
        return WorkflowGraph.builder(name).node("a", "TASK", Map.of()).build();
    }

    @Test
    void 注册与查询() {
        WorkflowRegistry registry = new WorkflowRegistry();
        registry.register(graph("w"), 1, 0);
        registry.register(graph("w"), 2, 50);
        assertEquals(1, registry.get("w", 1).version());
        assertEquals(50, registry.get("w", 2).canaryPercentage());
        assertNull(registry.get("w", 3));
        assertNull(registry.get("ghost", 1));
        assertEquals(2, registry.versions("w").size());
        // 重复注册拒绝
        assertThrows(IllegalArgumentException.class, () -> registry.register(graph("w"), 1, 0));
    }

    @Test
    void 单版本与零切流回退() {
        WorkflowRegistry registry = new WorkflowRegistry();
        registry.register(graph("w"), 1, 0);
        // 单版本：永远返回它
        assertEquals(1, registry.route("w", "tenant-a").version());
        // 多版本 + canary 0%：返回次高（默认版本）
        registry.register(graph("w"), 2, 0);
        assertEquals(1, registry.route("w", "tenant-a").version());
        // canary 100%：全量新版本
        registry.register(graph("x"), 1, 0);
        registry.register(graph("x"), 2, 100);
        assertEquals(2, registry.route("x", "tenant-a").version());
        // 未配置租户（null/空白）走默认版本
        assertEquals(1, registry.route("x", null).version());
        assertEquals(1, registry.route("x", "  ").version());
    }

    @Test
    void 稳定哈希与均匀性抽样() {
        // stickiness：同键多组值稳定
        for (int i = 0; i < 50; i++) {
            assertEquals(WorkflowRegistry.stableBucket("w", "tenant-" + i),
                    WorkflowRegistry.stableBucket("w", "tenant-" + i));
        }
        // 边界：桶值域 [0,100)
        for (int i = 0; i < 200; i++) {
            int bucket = WorkflowRegistry.stableBucket("w", "t" + i);
            assertTrue(bucket >= 0 && bucket < 100);
        }
        // 50% 切流：600 租户抽样，比例在 40%-60%（哈希均匀性粗校验）
        WorkflowRegistry registry = new WorkflowRegistry();
        registry.register(graph("w"), 1, 0);
        registry.register(graph("w"), 2, 50);
        int canary = 0;
        for (int i = 0; i < 600; i++) {
            if (registry.route("w", "tenant-" + i).version() == 2) {
                canary++;
            }
        }
        assertTrue(canary > 240 && canary < 360, "50% 切流实际命中 " + canary);
    }
}
