package cn.chyuan.ai.domain.scankernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BC5-BC8 单测（工单 0455-0458）：抑制基线/扫描报告/发现清单/扫描端口组合管线。
 */
class ScanGovernanceTest {

    private static final String SAMPLE = """
            public class A {
                void f() {
                    System.out.println("debug"); // nosemgrep
                    System.out.println(g);
                }
            }
            """;

    private static RuleParser.Rule printRule() {
        return new RuleParser.Rule("no-print", "java", RuleParser.Severity.WARN,
                "System.out.println($X)", List.of(), Set.of("X"));
    }

    @Test
    void 行内抑制与基线对比() {
        SuppressionBaseline suppression = new SuppressionBaseline();
        List<String> lines = List.of(
                "System.out.println(1);",
                "System.out.println(2); // nosemgrep",
                "System.out.println(3);");
        assertEquals(SuppressionBaseline.Status.OPEN, suppression.suppress(lines, 1));
        assertEquals(SuppressionBaseline.Status.SUPPRESSED, suppression.suppress(lines, 2));
        // 下一行豁免：第 2 行标注抑制第 3 行？——只抑制当前行与下一行，第 3 行自身无标注为 OPEN
        assertEquals(SuppressionBaseline.Status.OPEN, suppression.suppress(lines, 3));
        // 基线：基线内指纹转 BASELINE，新增保持 OPEN
        String fp = SuppressionBaseline.fingerprint("no-print", "A.java", 1, "System.out.println(1);");
        assertEquals(SuppressionBaseline.Status.BASELINE,
                suppression.baseline(SuppressionBaseline.Status.OPEN, fp, Set.of(fp)));
        assertEquals(SuppressionBaseline.Status.OPEN,
                suppression.baseline(SuppressionBaseline.Status.OPEN, fp, Set.of()));
    }

    @Test
    void 扫描端口组合管线() {
        ScanPort engine = new ScanPort.InMemoryScanEngine();
        Map<String, String> files = Map.of("A.java", SAMPLE);
        // 无基线：两条 println 命中，一条被行内抑制，一条 OPEN
        List<SuppressionBaseline.Finding> findings = engine.scan(files, List.of(printRule()), Set.of());
        assertEquals(2, findings.size());
        assertEquals(1, findings.stream().filter(f -> f.status() == SuppressionBaseline.Status.OPEN).count());
        assertEquals(1, findings.stream().filter(f -> f.status() == SuppressionBaseline.Status.SUPPRESSED).count());
        // 基线含 OPEN 指纹 → 全部非 OPEN
        String openFp = findings.stream().filter(f -> f.status() == SuppressionBaseline.Status.OPEN)
                .findFirst().orElseThrow().fingerprint();
        List<SuppressionBaseline.Finding> withBaseline = engine.scan(files, List.of(printRule()), Set.of(openFp));
        assertTrue(withBaseline.stream().noneMatch(f -> f.status() == SuppressionBaseline.Status.OPEN));
        assertTrue(withBaseline.stream().anyMatch(f -> f.status() == SuppressionBaseline.Status.BASELINE));
        // 空规则/空文件边界
        assertTrue(engine.scan(files, List.of(), Set.of()).isEmpty());
        assertTrue(engine.scan(Map.of(), List.of(printRule()), Set.of()).isEmpty());
    }

    @Test
    void 污点规则走端口与语言过滤() {
        ScanPort engine = new ScanPort.InMemoryScanEngine();
        RuleParser.Rule taint = new RuleParser.Rule("taint-sql", "any", RuleParser.Severity.ERROR,
                "taint(request.getParameter;escape;executeQuery)", List.of(), Set.of());
        Map<String, String> files = Map.of("D.java", """
                String q = request.getParameter("q");
                db.executeQuery(q);
                """);
        List<SuppressionBaseline.Finding> findings = engine.scan(files, List.of(taint), Set.of());
        assertEquals(1, findings.size());
        assertEquals(2, findings.get(0).line());
        assertEquals(RuleParser.Severity.ERROR.name(), findings.get(0).severity());
        // 语言过滤：java 规则不匹配 .py 文件
        RuleParser.Rule javaOnly = new RuleParser.Rule("no-print", "java", RuleParser.Severity.WARN,
                "System.out.println($X)", List.of(), Set.of("X"));
        assertTrue(engine.scan(Map.of("b.py", "System.out.println(1);"), List.of(javaOnly), Set.of()).isEmpty());
    }

    @Test
    void 报告聚合topN与确定性JSON() {
        List<SuppressionBaseline.Finding> findings = List.of(
                finding("r1", "a.java", "WARN"),
                finding("r1", "a.java", "WARN"),
                finding("r2", "b.java", "ERROR"),
                finding("r3", "b.java", "ERROR"));
        ScanReporter reporter = new ScanReporter();
        Map<String, Integer> byRule = reporter.aggregate(findings, "rule");
        assertEquals(2, byRule.get("r1"));
        // topN：计数降序同级字典序
        List<Map.Entry<String, Integer>> top1 = reporter.topN(byRule, 1);
        assertEquals("r1", top1.get(0).getKey());
        // 四口径
        Map<String, Integer> summary = reporter.summary(findings);
        assertEquals(4, summary.get("total"));
        assertEquals(4, summary.get("open"));
        assertEquals(0, summary.get("suppressed"));
        // JSON 重放一致
        assertEquals(reporter.toJson(findings, 1_000), reporter.toJson(findings, 1_000));
        assertTrue(reporter.toJson(findings, 1_000).contains("\"bySeverity\":{\"ERROR\":2,\"WARN\":2}"));
    }

    @Test
    void 发现清单批次幂等() {
        ScanFindingRegistry registry = new ScanFindingRegistry();
        List<SuppressionBaseline.Finding> batch = List.of(
                finding("r1", "a.java", "WARN"),
                finding("r2", "b.java", "ERROR"));
        assertEquals(2, registry.addBatch(batch, "scan-1"));
        // 同批次重放幂等
        assertEquals(0, registry.addBatch(batch, "scan-1"));
        // 跨批次同指纹也幂等
        assertEquals(0, registry.addBatch(batch, "scan-2"));
        assertEquals(2, registry.size());
        // 新指纹新批次可入
        assertEquals(1, registry.addBatch(List.of(finding("r3", "c.java", "INFO")), "scan-2"));
    }

    private static SuppressionBaseline.Finding finding(String ruleId, String file, String severity) {
        return new SuppressionBaseline.Finding(
                SuppressionBaseline.fingerprint(ruleId, file, 1, "content"),
                ruleId, file, 1, 1, severity, SuppressionBaseline.Status.OPEN, "content");
    }
}
