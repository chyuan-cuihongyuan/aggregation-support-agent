package cn.chyuan.ai.domain.scankernel.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 污点传播简化（工单 0454 BC4，semgrep 污点模式过程内简化）。
 * 语句级：source 调用标记赋值左值污染 → 赋值/传参传播 → sanitizer 调用净化
 * → sink 调用携带污染实参报告（行+变量+路径）。线性扫描单遍收敛。纯函数。
 */
public class TaintTracker {

    /** 污点规则 */
    public record TaintRule(List<String> sources, List<String> sanitizers, List<String> sinks) {
    }

    /** 污点发现 */
    public record TaintFinding(int line, String variable, String sink, String path) {
    }

    public List<TaintFinding> track(List<String> lines, TaintRule rule) {
        Set<String> tainted = new HashSet<>();
        List<String> path = new ArrayList<>();
        List<TaintFinding> findings = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String source = matchAny(line, rule.sources());
            if (source != null) {
                String variable = leftHandSide(line);
                if (variable != null) {
                    tainted.add(variable);
                    path.add("L" + (i + 1) + ": " + variable + " = " + source + "(...)");
                }
            }
            for (String variable : List.copyOf(tainted)) {
                if (line.contains(variable)) {
                    String sanitizer = matchAny(line, rule.sanitizers());
                    if (sanitizer != null) {
                        tainted.remove(variable);
                        path.add("L" + (i + 1) + ": " + variable + " 净化(" + sanitizer + ")");
                    }
                }
            }
            String sink = matchAny(line, rule.sinks());
            if (sink != null) {
                for (String variable : tainted) {
                    if (line.contains(variable)) {
                        findings.add(new TaintFinding(i + 1, variable, sink,
                                String.join(" -> ", path) + " -> L" + (i + 1) + ": sink " + sink));
                    }
                }
            }
            String plainAssignment = leftHandSide(line);
            if (plainAssignment != null && !tainted.contains(plainAssignment)) {
                // 赋值传播：右值引用污染变量则左值污染
                String rhs = rightHandSide(line);
                for (String variable : tainted) {
                    if (rhs.contains(variable) && matchAny(line, rule.sanitizers()) == null) {
                        tainted.add(plainAssignment);
                        path.add("L" + (i + 1) + ": " + plainAssignment + " <- " + variable);
                        break;
                    }
                }
            }
        }
        return findings;
    }

    private static String matchAny(String line, List<String> functions) {
        for (String function : functions) {
            if (!function.isBlank() && line.contains(function)) {
                return function;
            }
        }
        return null;
    }

    /** 赋值左值：取 '=' 左侧最后一个标识符 */
    static String leftHandSide(String line) {
        int eq = line.indexOf('=');
        if (eq <= 0 || line.charAt(eq - 1) == '=' || (eq + 1 < line.length() && line.charAt(eq + 1) == '=')) {
            return null;
        }
        String left = line.substring(0, eq).strip();
        String[] parts = left.split("[\\s]+");
        String candidate = parts[parts.length - 1];
        return candidate.matches("[A-Za-z_][A-Za-z0-9_]*") ? candidate : null;
    }

    static String rightHandSide(String line) {
        int eq = line.indexOf('=');
        return eq < 0 ? "" : line.substring(eq + 1);
    }
}
