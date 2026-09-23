package cn.chyuan.ai.domain.resolvekernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 依赖解析内核域单测（工单 0663-0669 CA1-CA7，uv PubGrub 思想）。
 * semver 解析全序与预发布规则/约束算子 ^~ 语义/区间交并空集/
 * 单元传播唯一版本拍板/冲突即时失败/责任链回跳/穷尽冲突解释/
 * 锁文件确定性重解析一致与最小变更。
 */
class ResolveKernelTest {

    static ResolvePort.CandidateSource source(Map<String, List<String>> versions, Map<String, List<String>> deps) {
        return new ResolvePort.CandidateSource() {
            @Override
            public List<String> versions(String name) {
                return versions.getOrDefault(name, List.of());
            }

            @Override
            public List<String> dependencies(String name, String version) {
                return deps.getOrDefault(name + "@" + version, List.of());
            }
        };
    }

    @Test
    void semverParseAndTotalOrdering() {
        assertTrue(Semver.of("1.2.3").compareTo(Semver.of("1.2.10")) < 0);
        assertTrue(Semver.of("1.2.10").compareTo(Semver.of("1.10.0")) < 0);
        assertTrue(Semver.of("1.10.0").compareTo(Semver.of("2.0.0")) < 0);
        assertTrue(Semver.of("1.0.0-alpha").compareTo(Semver.of("1.0.0")) < 0, "无预发布 > 有预发布");
        assertTrue(Semver.of("1.0.0-alpha").compareTo(Semver.of("1.0.0-alpha.1")) < 0, "段多者大");
        assertTrue(Semver.of("1.0.0-alpha.1").compareTo(Semver.of("1.0.0-beta")) < 0, "数字段低于字母段");
        assertTrue(Semver.of("1.0.0-alpha.1").compareTo(Semver.of("1.0.0-alpha.beta")) < 0);
        assertEquals(0, Semver.of("1.0.0+build.1").compareTo(Semver.of("1.0.0+other")), "构建元数据不参与比较");
        assertEquals("1.2.3-alpha.1+b7", Semver.of("1.2.3-alpha.1+b7").toString());
    }

    @Test
    void semverRejectsIllegal() {
        for (String bad : new String[]{"1.2", "01.2.3", "1.2.3-", "1.2.3-alpha..1", "", "1.2.x", "1.2.3.4"}) {
            assertThrows(IllegalArgumentException.class, () -> Semver.of(bad), "应拒绝：" + bad);
        }
        assertThrows(IllegalArgumentException.class, () -> Semver.of(null));
    }

    @Test
    void constraintSpecifiersCaretTilde() {
        assertTrue(Range.of(">=1.2.0").satisfiedBy(Semver.of("1.2.0")));
        assertFalse(Range.of(">=1.2.0").satisfiedBy(Semver.of("1.1.9")));
        Range caret = Range.of("^1.2.3");
        assertTrue(caret.satisfiedBy(Semver.of("1.2.3")));
        assertTrue(caret.satisfiedBy(Semver.of("1.9.9")));
        assertFalse(caret.satisfiedBy(Semver.of("2.0.0")), "^1 越大版本拒绝");
        assertFalse(caret.satisfiedBy(Semver.of("1.2.2")));
        Range caretZero = Range.of("^0.5.2");
        assertTrue(caretZero.satisfiedBy(Semver.of("0.5.9")));
        assertFalse(caretZero.satisfiedBy(Semver.of("0.6.0")), "^0 锁小版本位");
        Range tilde = Range.of("~1.3.2");
        assertTrue(tilde.satisfiedBy(Semver.of("1.3.9")));
        assertFalse(tilde.satisfiedBy(Semver.of("1.4.0")), "~ 锁次版本位");
        assertTrue(Range.of("!=1.2.3").satisfiedBy(Semver.of("1.2.4")));
        assertFalse(Range.of("!=1.2.3").satisfiedBy(Semver.of("1.2.3")));
        assertTrue(Range.of("1.2.3").satisfiedBy(Semver.of("1.2.3")), "裸版本视作 ==");
        assertTrue(Range.of("*").satisfiedBy(Semver.of("9.9.9")));
        assertThrows(IllegalArgumentException.class, () -> Range.of(">="));
    }

    @Test
    void rangeIntersectEmptyAndNormalized() {
        Range r1 = Range.of(">=1.0.0", "<3.0.0");
        Range r2 = Range.of(">=2.5.0");
        Range inter = r1.intersect(r2);
        assertTrue(inter.satisfiedBy(Semver.of("2.5.0")));
        assertFalse(inter.satisfiedBy(Semver.of("1.5.0")));
        assertTrue(Range.of("<1.0.0").intersect(Range.of(">2.0.0")).isEmpty(), "不相交区间为空集");
        List<Range.Bound[]> norm = Range.of("^1.2.3").normalized();
        assertEquals(1, norm.size());
        assertEquals(Semver.of("1.2.3"), norm.get(0)[0].version());
        assertEquals(Semver.of("2.0.0"), norm.get(0)[1].version());
        assertTrue(Range.of(">2.0.0", "<1.0.0").normalized().isEmpty(), "空集规范化为空清单");
    }

    @Test
    void resolutionUnitPropagationAssignsOnlyVersion() {
        ResolvePort.CandidateSource src = source(
                Map.of("A", List.of("1.0.0", "1.5.0", "2.0.0"), "B", List.of("1.0.0", "0.9.0")),
                Map.of("A@1.0.0", List.of("B >=1.0.0")));
        Resolver resolver = new Resolver(src, null, 10_000);
        Resolver.Outcome outcome = resolver.solve(Map.of("A", "==1.0.0"));
        assertEquals("SAT", outcome.status());
        assertEquals("1.0.0", outcome.assigned().get("A"));
        assertEquals("1.0.0", outcome.assigned().get("B"), "B 唯一满足 >=1.0.0 者被单元传播拍板");
        assertTrue(outcome.propagations() >= 2);
    }

    @Test
    void conflictImmediateFailureWithNoCandidate() {
        ResolvePort.CandidateSource src = source(Map.of("A", List.of("1.0.0", "2.0.0")), Map.of());
        Resolver resolver = new Resolver(src, null, 10_000);
        Resolver.Outcome outcome = resolver.solve(Map.of("A", ">=3.0.0"));
        assertEquals("CONFLICT", outcome.status());
        assertEquals("A", outcome.conflict().name());
        assertTrue(Resolver.explain(outcome.conflict()).contains("root"), "冲突链含 root 需求");
    }

    @Test
    void backjumpTriesResponsibleDecisionNextVersion() {
        ResolvePort.CandidateSource src = source(
                Map.of("A", List.of("2.0.0", "1.0.0"), "C", List.of("1.0.0", "2.0.0")),
                Map.of("A@2.0.0", List.of("C >=2.0.0"), "A@1.0.0", List.of("C >=1.0.0")));
        Resolver resolver = new Resolver(src, null, 10_000);
        Resolver.Outcome outcome = resolver.solve(Map.of("A", "*", "C", "==1.0.0"));
        assertEquals("SAT", outcome.status());
        assertEquals("1.0.0", outcome.assigned().get("A"), "A@2.0.0 与 C 冲突后回跳试 A@1.0.0");
        assertEquals("1.0.0", outcome.assigned().get("C"));
        assertTrue(outcome.backtracks() >= 1, "应有回跳记录");
    }

    @Test
    void exhaustionProducesConflictExplanation() {
        ResolvePort.CandidateSource src = source(
                Map.of("A", List.of("2.0.0", "1.0.0"), "B", List.of("1.0.0")),
                Map.of("A@2.0.0", List.of("B >=2.0.0"), "A@1.0.0", List.of("B >=3.0.0")));
        Resolver resolver = new Resolver(src, null, 10_000);
        Resolver.Outcome outcome = resolver.solve(Map.of("A", "*"));
        assertEquals("CONFLICT", outcome.status());
        assertEquals("A", outcome.conflict().name(), "A 全版本穷尽后冲突归属 A");
        String explanation = Resolver.explain(outcome.conflict());
        assertTrue(explanation.startsWith("无法解析 A"), explanation);
        assertTrue(explanation.contains("root 需要"), explanation);
    }

    @Test
    void lockfileDeterministicReparsesEqual() {
        ResolvePort.CandidateSource src = source(
                Map.of("A", List.of("1.0.0"), "B", List.of("2.0.0")),
                Map.of("A@1.0.0", List.of("B >=1.0.0")));
        Resolver resolver = new Resolver(src, null, 10_000);
        Resolver.Outcome outcome = resolver.solve(Map.of("A", "*", "B", "*"));
        Lockfile lock = Lockfile.from(outcome.assigned(), src);
        String text1 = lock.toCanonicalText();
        String text2 = lock.toCanonicalText();
        assertEquals(text1, text2, "同解两次序列化确定一致");
        assertEquals(lock, Lockfile.parse(text1), "重解析一致性");
        assertEquals("A", lock.sorted().get(0).name(), "字典序输出");
        assertTrue(lock.get("A").digest().length() == 16, "SHA-256 内容指纹截断 16 位");
    }

    @Test
    void lockDiffMinimalChanges() {
        Lockfile oldLock = Lockfile.parse("==A||1.0.0\n==B||1.0.0\n==C||2.0.0\n");
        Lockfile newLock = Lockfile.parse("==A||1.5.0\n==B||1.0.0\n==D||1.0.0\n");
        Lockfile.Diff diff = Lockfile.diff(oldLock, newLock);
        assertEquals(1, diff.added().size());
        assertEquals("D", diff.added().get(0).name());
        assertEquals(1, diff.removed().size());
        assertEquals("C", diff.removed().get(0).name());
        assertEquals(1, diff.changed().size());
        assertEquals("1.0.0", diff.changed().get(0)[0].version());
        assertEquals("1.5.0", diff.changed().get(0)[1].version());
        assertThrows(IllegalArgumentException.class, () -> Lockfile.diff(null, newLock));
    }

    @Test
    void preferLockedVersionFirst() {
        ResolvePort.CandidateSource src = source(Map.of("A", List.of("2.0.0", "1.5.0", "1.0.0")), Map.of());
        Resolver resolver = new Resolver(src, Map.of("A", "1.0.0"), 10_000);
        Resolver.Outcome outcome = resolver.solve(Map.of("A", "*"));
        assertEquals("SAT", outcome.status());
        assertEquals("1.0.0", outcome.assigned().get("A"), "已锁版本在偏好下胜出（非最高版）");
    }

    @Test
    void explanationChainRootFirstShortest() {
        ResolvePort.CandidateSource src = source(
                Map.of("A", List.of("1.0.0"), "B", List.of("1.0.0")),
                Map.of("A@1.0.0", List.of("B >=2.0.0")));
        Resolver resolver = new Resolver(src, null, 10_000);
        Resolver.Outcome outcome = resolver.solve(Map.of("A", "*", "B", "<2.0.0"));
        assertEquals("CONFLICT", outcome.status());
        String explanation = Resolver.explain(outcome.conflict());
        assertTrue(explanation.indexOf("root") < explanation.indexOf("A@"), "root 链先于传递需求（最短链优先）");
        assertTrue(explanation.endsWith("（无同时满足的候选版本）"));
    }

    @Test
    void stepLimitStopsAsLimit() {
        ResolvePort.CandidateSource src = source(
                Map.of("A", List.of("2.0.0", "1.0.0"), "B", List.of("2.0.0", "1.0.0")),
                Map.of("A@2.0.0", List.of("B *"), "A@1.0.0", List.of("B *")));
        Resolver resolver = new Resolver(src, null, 1);
        Resolver.Outcome outcome = resolver.solve(Map.of("A", "*"));
        assertEquals("LIMIT", outcome.status(), "节点熔断触发");
    }
}
