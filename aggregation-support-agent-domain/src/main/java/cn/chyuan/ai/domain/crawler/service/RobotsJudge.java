package cn.chyuan.ai.domain.crawler.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * robots.txt 合规判定（工单 0419 AY1，crawl4ai 合规思想）。
 * 指令解析（User-agent/Allow/Disallow/Crawl-delay 分组）+ 路径最长匹配优先级
 * （Allow/Disallow 同长取 Allow）+ 通配符 {@code *} 与行尾 {@code $} 子集。
 * 空/非法文本按全放行处理。时钟无关纯函数。
 */
public class RobotsJudge {

    /** 判定结果 */
    public record Verdict(boolean allowed, String matchedRule) {
    }

    /** 单条路径规则 */
    record PathRule(boolean allow, String pattern) {
    }

    /** 一组 User-agent 的规则 */
    static final class AgentGroup {
        final List<String> agents = new ArrayList<>();
        final List<PathRule> rules = new ArrayList<>();
        Long crawlDelayMs;
    }

    /** 解析产物：agent 名（小写）→ 规则组 */
    private final Map<String, AgentGroup> groups = new LinkedHashMap<>();

    public RobotsJudge(String robotsText) {
        if (robotsText == null || robotsText.isBlank()) {
            return;
        }
        AgentGroup current = null;
        for (String rawLine : robotsText.split("\r?\n")) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#") || !line.contains(":")) {
                continue;
            }
            int colon = line.indexOf(':');
            String field = line.substring(0, colon).strip().toLowerCase();
            String value = line.substring(colon + 1).strip();
            switch (field) {
                case "user-agent" -> {
                    String agent = value.toLowerCase();
                    current = groups.computeIfAbsent(agent, k -> new AgentGroup());
                    current.agents.add(agent);
                }
                case "allow" -> {
                    if (current != null && !value.isEmpty()) {
                        current.rules.add(new PathRule(true, value));
                    }
                }
                case "disallow" -> {
                    if (current != null && !value.isEmpty()) {
                        current.rules.add(new PathRule(false, value));
                    }
                }
                case "crawl-delay" -> {
                    if (current != null) {
                        try {
                            current.crawlDelayMs = (long) (Double.parseDouble(value) * 1000);
                        } catch (NumberFormatException ignored) {
                            // 非法 crawl-delay 忽略
                        }
                    }
                }
                default -> {
                    // sitemap 等其它字段忽略
                }
            }
        }
    }

    /**
     * 判定 (ua, path)：精确 agent 组优先于 {@code *} 组；组内最长匹配规则胜出，
     * 同长 Allow 优先；无命中或无规则组放行。
     */
    public Verdict judge(String userAgent, String path) {
        AgentGroup group = groups.get(userAgent == null ? "" : userAgent.toLowerCase());
        if (group == null) {
            group = groups.get("*");
        }
        if (group == null || group.rules.isEmpty()) {
            return new Verdict(true, null);
        }
        PathRule best = null;
        for (PathRule rule : group.rules) {
            if (!matches(rule.pattern(), path)) {
                continue;
            }
            if (best == null || rule.pattern().length() > best.pattern().length()
                    || (rule.pattern().length() == best.pattern().length() && rule.allow())) {
                best = rule;
            }
        }
        return best == null ? new Verdict(true, null) : new Verdict(best.allow(), best.pattern());
    }

    /** 组的 crawl-delay 毫秒（精确组缺失时回退 * 组，仍无则 null） */
    public Long crawlDelayMs(String userAgent) {
        AgentGroup group = groups.get(userAgent == null ? "" : userAgent.toLowerCase());
        if (group == null) {
            group = groups.get("*");
        }
        return group == null ? null : group.crawlDelayMs;
    }

    /** 通配子集匹配：{@code *} 任意序列、行尾 {@code $} 锚定，其余按前缀字面 */
    static boolean matches(String pattern, String path) {
        boolean anchored = pattern.endsWith("$");
        String core = anchored ? pattern.substring(0, pattern.length() - 1) : pattern;
        int star = core.indexOf('*');
        if (star < 0) {
            // 无通配：前缀匹配；锚定时须整段相等
            return anchored ? path.equals(core) : path.startsWith(core);
        }
        String head = core.substring(0, star);
        String tail = core.substring(star + 1);
        if (!path.startsWith(head)) {
            return false;
        }
        String rest = path.substring(head.length());
        if (tail.isEmpty()) {
            return !anchored || rest.isEmpty();
        }
        return anchored ? rest.endsWith(tail) : rest.contains(tail);
    }
}
