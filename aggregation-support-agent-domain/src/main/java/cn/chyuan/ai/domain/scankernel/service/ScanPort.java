package cn.chyuan.ai.domain.scankernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 扫描执行端口（工单 0458 BC8）+ 内存假实现组合管线。
 * scan(files, rules, baseline)→findings：规则解析 → 模式/污点匹配 → 行内抑制 → 基线对比。
 * scan-kernel.enabled 默认关不接 Mimosa 链路（0417-D6 只读借鉴）。
 */
public interface ScanPort {

    /** 扫描发现 */
    List<SuppressionBaseline.Finding> scan(Map<String, String> files,
                                           List<RuleParser.Rule> rules,
                                           Set<String> baselineFingerprints);

    /** 内存假实现：组合管线（规则解析由调用方完成，此处按语言过滤+匹配+污点） */
    class InMemoryScanEngine implements ScanPort {

        private final PatternMatcher matcher = new PatternMatcher();
        private final TaintTracker tracker = new TaintTracker();
        private final SuppressionBaseline suppression = new SuppressionBaseline();

        @Override
        public List<SuppressionBaseline.Finding> scan(Map<String, String> files,
                                                      List<RuleParser.Rule> rules,
                                                      Set<String> baselineFingerprints) {
            List<SuppressionBaseline.Finding> findings = new ArrayList<>();
            for (Map.Entry<String, String> file : files.entrySet()) {
                String path = file.getKey();
                String content = file.getValue();
                List<String> lines = List.of(content.split("\n", -1));
                String language = languageOf(path);
                for (RuleParser.Rule rule : rules) {
                    if (!"any".equals(rule.language()) && !rule.language().equals(language)) {
                        continue;
                    }
                    List<int[]> rawHits = new ArrayList<>();
                    if (rule.pattern().startsWith("taint(")) {
                        for (SuppressionBaseline.Finding parsed : taintFindings(rule, path, lines)) {
                            findings.add(parsed);
                        }
                        continue;
                    }
                    List<PatternMatcher.Token> tokens = PatternMatcher.tokenize(content);
                    for (PatternMatcher.Match match : matcher.findAll(tokens, rule.pattern())) {
                        rawHits.add(new int[]{match.line(), match.column()});
                    }
                    for (int[] hit : rawHits) {
                        String lineContent = lines.get(hit[0] - 1);
                        if (excluded(lineContent, rule.excludes())) {
                            continue;
                        }
                        SuppressionBaseline.Status status = suppression.suppress(lines, hit[0]);
                        String fingerprint = SuppressionBaseline.fingerprint(rule.id(), path, hit[0], lineContent);
                        status = suppression.baseline(status, fingerprint, baselineFingerprints);
                        findings.add(new SuppressionBaseline.Finding(fingerprint, rule.id(), path,
                                hit[0], hit[1], rule.severity().name(), status, lineContent.strip()));
                    }
                }
            }
            return findings;
        }

        /** 污点规则：pattern 形如 taint(src1|src2;san;sink1|sink2) */
        private List<SuppressionBaseline.Finding> taintFindings(RuleParser.Rule rule, String path,
                                                                List<String> lines) {
            String body = rule.pattern().substring("taint(".length(), rule.pattern().length() - 1);
            String[] parts = body.split(";");
            TaintTracker.TaintRule taintRule = new TaintTracker.TaintRule(
                    List.of(parts[0].split("\\|")), List.of(parts[1].split("\\|")), List.of(parts[2].split("\\|")));
            List<SuppressionBaseline.Finding> findings = new ArrayList<>();
            for (TaintTracker.TaintFinding finding : tracker.track(lines, taintRule)) {
                String lineContent = lines.get(finding.line() - 1);
                SuppressionBaseline.Status status = suppression.suppress(lines, finding.line());
                String fingerprint = SuppressionBaseline.fingerprint(rule.id(), path, finding.line(), lineContent);
                status = suppression.baseline(status, fingerprint, Set.of());
                findings.add(new SuppressionBaseline.Finding(fingerprint, rule.id(), path,
                        finding.line(), 1, rule.severity().name(), status, lineContent.strip()));
            }
            return findings;
        }

        private static boolean excluded(String lineContent, List<String> excludes) {
            for (String exclude : excludes) {
                if (lineContent.contains(exclude)) {
                    return true;
                }
            }
            return false;
        }

        static String languageOf(String path) {
            String lower = path.toLowerCase();
            if (lower.endsWith(".java")) {
                return "java";
            }
            if (lower.endsWith(".py")) {
                return "python";
            }
            if (lower.endsWith(".js")) {
                return "js";
            }
            return "other";
        }
    }
}
