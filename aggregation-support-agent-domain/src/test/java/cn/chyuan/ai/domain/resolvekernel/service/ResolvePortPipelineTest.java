package cn.chyuan.ai.domain.resolvekernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ResolvePort 组合管线测试（工单 0670 CA8，uv PubGrub 思想）。
 * 菱形依赖解析收敛/锁文件文本 vcskernel 只读联动形态/
 * 已锁偏好最小变更/UNSAT 解释与 LIMIT 熔断。
 */
class ResolvePortPipelineTest {

    @Test
    void portDiamondResolveAndCommitText() {
        ResolvePort.CandidateSource src = ResolveKernelTest.source(
                Map.of("A", List.of("1.0.0"), "B", List.of("1.0.0"),
                        "D", List.of("2.0.0", "1.5.0", "1.0.0")),
                Map.of("A@1.0.0", List.of("D >=1.0.0 <2.0.0"),
                        "B@1.0.0", List.of("D >=1.5.0")));
        ResolvePort port = new ResolvePort.InMemoryResolver();
        ResolvePort.Result result = port.resolve(Map.of("A", "*", "B", "*"), src);
        assertEquals(ResolvePort.Status.SAT, result.status());
        assertEquals("1.5.0", result.pinned().get("D"), "两路约束交集取 1.5.0");
        assertEquals(3, result.lock().size());
        String commitText = port.lockTextForCommit(result);
        assertEquals(result.lock().toCanonicalText(), commitText, "联动文本与锁序列化一致");
        assertTrue(commitText.startsWith("==A|"), "vcskernel blob 输入形态（行协议）");
        assertTrue(result.stat().propagations() + result.stat().decisions() > 0);
    }

    @Test
    void portResolveWithoutLockTakesHighest() {
        ResolvePort.CandidateSource src = ResolveKernelTest.source(
                Map.of("A", List.of("2.0.0", "1.0.0"), "B", List.of("1.0.0")),
                Map.of());
        ResolvePort port = new ResolvePort.InMemoryResolver();
        ResolvePort.Result first = port.resolve(Map.of("A", "*", "B", "*"), src);
        assertEquals(ResolvePort.Status.SAT, first.status());
        assertEquals("2.0.0", first.pinned().get("A"), "无偏好默认取最高版本");
    }

    @Test
    void portResolveWithLockPrefersPrevious() {
        ResolvePort.CandidateSource src = ResolveKernelTest.source(
                Map.of("A", List.of("2.0.0", "1.0.0"), "B", List.of("1.0.0")),
                Map.of());
        ResolvePort port = new ResolvePort.InMemoryResolver();
        Lockfile previous = Lockfile.parse("==A||1.0.0\n");
        ResolvePort.Result result = port.resolveWithLock(Map.of("A", "*", "B", "*"), src, previous);
        assertEquals(ResolvePort.Status.SAT, result.status());
        assertEquals("1.0.0", result.pinned().get("A"), "已锁版本优先复用（非最高版）");
        Lockfile.Diff diff = Lockfile.diff(previous, result.lock());
        assertEquals(1, diff.added().size(), "仅新增 B");
        assertEquals("B", diff.added().get(0).name());
        assertEquals(0, diff.changed().size(), "A 保持不变（最小变更）");
    }

    @Test
    void portUnsatExplanationAndRejects() {
        ResolvePort.CandidateSource src = ResolveKernelTest.source(
                Map.of("A", List.of("1.0.0")), Map.of());
        ResolvePort port = new ResolvePort.InMemoryResolver();
        ResolvePort.Result result = port.resolve(Map.of("A", ">=2.0.0"), src);
        assertEquals(ResolvePort.Status.UNSAT, result.status());
        assertNull(result.lock());
        assertNotNull(result.explanation());
        assertTrue(result.explanation().startsWith("无法解析 A"));
        assertEquals(1, result.stat().conflicts());
        assertThrows(IllegalArgumentException.class, () -> port.resolve(Map.of("A", "*"), null));
        assertThrows(IllegalArgumentException.class, () -> port.lockTextForCommit(result), "无锁不可提交");
    }
}
