package cn.chyuan.ai.domain.scankernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BC1-BC4 单测（工单 0451-0454）：规则 DSL/模式匹配/metavariable/污点传播。
 */
class ScanKernelTest {

    @Test
    void 规则解析与拒绝() {
        RuleParser parser = new RuleParser();
        List<RuleParser.Rule> rules = parser.parse(List.of(
                new RuleParser.RuleDefinition("r1", "java", "ERROR", "System.out.println($X)", List.of()),
                new RuleParser.RuleDefinition("r2", "any", "WARN", "$X == $X", List.of("test"))));
        assertEquals(2, rules.size());
        assertEquals(RuleParser.Severity.ERROR, rules.get(0).severity());
        assertTrue(rules.get(0).metavariables().contains("X"));
        assertTrue(rules.get(1).metavariables().containsAll(Set.of("X")));
        // 重复 id
        assertThrows(RuleParser.RuleParseException.class, () -> parser.parse(List.of(
                new RuleParser.RuleDefinition("r1", "java", "ERROR", "a", List.of()),
                new RuleParser.RuleDefinition("r1", "java", "ERROR", "b", List.of()))));
        // 空 pattern
        assertThrows(RuleParser.RuleParseException.class, () -> parser.parse(List.of(
                new RuleParser.RuleDefinition("r3", "java", "ERROR", " ", List.of()))));
        // 非法 severity
        assertThrows(RuleParser.RuleParseException.class, () -> parser.parse(List.of(
                new RuleParser.RuleDefinition("r4", "java", "FATAL", "a", List.of()))));
        // 非法 metavariable 名
        assertThrows(RuleParser.RuleParseException.class, () -> parser.parse(List.of(
                new RuleParser.RuleDefinition("r5", "java", "ERROR", "$bad", List.of()))));
    }

    @Test
    void 模式匹配通配绑定定位() {
        List<PatternMatcher.Token> tokens = PatternMatcher.tokenize("foo(1);\nbar(foo(2), x);");
        PatternMatcher matcher = new PatternMatcher();
        // 字面 + metavariable + ...
        List<PatternMatcher.Match> hits = matcher.findAll(tokens, "foo($V)");
        assertEquals(2, hits.size());
        assertEquals(1, hits.get(0).line());
        assertEquals("1", hits.get(0).bindings().get("V"));
        assertEquals(2, hits.get(1).line());
        assertEquals("2", hits.get(1).bindings().get("V"));
        // ... 序列（含空）
        List<PatternMatcher.Match> spread = matcher.findAll(tokens, "bar(..., $A)");
        assertEquals(1, spread.size());
        assertEquals("x", spread.get(0).bindings().get("A"));
        // 同名绑定一致：foo($X, $X) 不命中异值
        assertTrue(matcher.findAll(tokens, "foo($X, $X)").isEmpty());
        // 不匹配
        assertTrue(matcher.findAll(tokens, "baz($X)").isEmpty());
    }

    @Test
    void 绑定冲突与数值比较() {
        MetavarBinding binding = new MetavarBinding();
        MetavarBinding.MapBinding env = new MetavarBinding.MapBinding();
        env.put("X", "1");
        assertTrue(binding.conflict(env, "X", "1").isEmpty());
        assertTrue(binding.conflict(env, "X", "2").isPresent());
        // $X == $X：同绑定恒真
        assertEquals(Boolean.TRUE, binding.compare(env.get("X"), "==", env.get("X")));
        assertEquals(Boolean.FALSE, binding.compare("1", "==", "2"));
        // 数值比较
        assertEquals(Boolean.TRUE, binding.compare("3", ">=", "3"));
        assertEquals(Boolean.FALSE, binding.compare("2", ">", "3"));
        // 未知操作
        assertThrows(IllegalArgumentException.class, () -> binding.compare("1", "===", "1"));
    }

    @Test
    void 污点传播净化与sink报告() {
        TaintTracker tracker = new TaintTracker();
        List<String> lines = List.of(
                "String name = request.getParameter(\"name\");",
                "String upper = name;",
                "String safe = escape(name);",
                "db.executeQuery(upper);",
                "db.executeQuery(safe);");
        TaintTracker.TaintRule rule = new TaintTracker.TaintRule(
                List.of("request.getParameter"), List.of("escape"), List.of("executeQuery"));
        List<TaintTracker.TaintFinding> findings = tracker.track(lines, rule);
        // 污点路径 name→upper 到 sink；safe 净化不报
        assertEquals(1, findings.size());
        assertEquals(4, findings.get(0).line());
        assertEquals("upper", findings.get(0).variable());
        assertTrue(findings.get(0).path().contains("escape"));
        // 无 source 无报告
        assertTrue(tracker.track(List.of("db.executeQuery(x);"), rule).isEmpty());
    }
}
