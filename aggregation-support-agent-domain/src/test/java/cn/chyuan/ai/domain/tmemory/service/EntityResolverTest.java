package cn.chyuan.ai.domain.tmemory.service;

import cn.chyuan.ai.domain.tmemory.model.valobj.MemoryEdgeVO;
import cn.chyuan.ai.domain.tmemory.model.valobj.MergePlanVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AS2 单测（工单 0363）：归一键/别名/类型冲突/边重定向完整性/幂等。
 */
class EntityResolverTest {

    @Test
    void 归一键与别名命中合并() {
        EntityResolver resolver = new EntityResolver(Map.of("鸿蒙系统", "HarmonyOS"));
        MergePlanVO plan = resolver.resolve(List.of(
                new EntityResolver.Entity("HarmonyOS", "系统"),
                new EntityResolver.Entity("harmony os", "系统"),
                new EntityResolver.Entity("鸿蒙系统", "系统"),
                new EntityResolver.Entity("华为", "公司")));
        // 全角折半+空白去除+小写：harmony os 与 HarmonyOS 同键；鸿蒙系统别名归并
        assertEquals(2, plan.getGroups().size());
        MergePlanVO.Group merged = plan.getGroups().get(0);
        assertEquals(3, merged.getMembers().size());
        assertEquals("HarmonyOS", merged.getCanonicalName());
        assertEquals("norm-key", merged.getReason());
    }

    @Test
    void 类型冲突拒绝合并保持分离() {
        EntityResolver resolver = new EntityResolver(Map.of());
        MergePlanVO plan = resolver.resolve(List.of(
                new EntityResolver.Entity("苹果", "公司"),
                new EntityResolver.Entity("苹果", "水果")));
        assertEquals(2, plan.getGroups().size());
        assertEquals("type-conflict-kept", plan.getGroups().get(1).getReason());
        assertEquals("苹果", plan.getRedirect().get("苹果"));
    }

    @Test
    void 边重定向完整性与幂等() {
        BiTemporalEdgeFactory factory = new BiTemporalEdgeFactory();
        EntityResolver resolver = new EntityResolver(Map.of("鸿蒙系统", "HarmonyOS"));
        MemoryEdgeVO edge1 = factory.create("鸿蒙系统", "开发商是", "华为", 1L, null, 0.9, "official", "SEMANTIC");
        MemoryEdgeVO edge2 = factory.create("harmony os", "发布于", "2千2百年", 2L, null, 0.8, "media", "EPISODIC");
        MergePlanVO plan = resolver.resolve(List.of(
                new EntityResolver.Entity("HarmonyOS", "系统"),
                new EntityResolver.Entity("harmony os", "系统"),
                new EntityResolver.Entity("鸿蒙系统", "系统"),
                new EntityResolver.Entity("华为", "公司")));
        List<MemoryEdgeVO> redirected = resolver.apply(List.of(edge1, edge2), plan);
        assertEquals("HarmonyOS", redirected.get(0).getSubject());
        assertEquals("HarmonyOS", redirected.get(1).getSubject());
        // 无悬空端点：所有端点要么被重定向要么保留原名，无 null
        for (MemoryEdgeVO edge : redirected) {
            assertTrue(edge.getSubject() != null && edge.getObject() != null);
        }
        // 幂等：二次应用结果一致
        List<MemoryEdgeVO> twice = resolver.apply(redirected, plan);
        assertEquals(redirected, twice);
    }
}
