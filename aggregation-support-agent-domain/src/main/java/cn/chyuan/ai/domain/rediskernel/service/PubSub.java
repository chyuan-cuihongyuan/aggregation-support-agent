package cn.chyuan.ai.domain.rediskernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 发布订阅（工单 0652 BY6，redis 思想）。
 * 频道精确订阅与退订/pattern 通配订阅（glob：* ? [seq] [a-z] [^...]）退订/
 * publish 返回触达投递数（每条匹配订阅计一次）/消息不持久化（仅入收件箱日志）。
 */
public final class PubSub {

    /** 一条投递：订阅者、来源频道/pattern、消息 */
    public record Delivery(String subscriber, String source, boolean byPattern, String message) {
    }

    private final Map<String, Set<String>> channels = new HashMap<>();
    private final Map<String, Set<String>> patterns = new HashMap<>();
    private final List<Delivery> deliveries = new ArrayList<>();

    public void subscribe(String channel, String subscriber) {
        require(channel, "频道");
        require(subscriber, "订阅者");
        channels.computeIfAbsent(channel, k -> new LinkedHashSet<>()).add(subscriber);
    }

    public void unsubscribe(String channel, String subscriber) {
        require(channel, "频道");
        require(subscriber, "订阅者");
        Set<String> subs = channels.get(channel);
        if (subs != null) {
            subs.remove(subscriber);
            if (subs.isEmpty()) {
                channels.remove(channel);
            }
        }
    }

    public void psubscribe(String pattern, String subscriber) {
        require(pattern, "pattern");
        require(subscriber, "订阅者");
        patterns.computeIfAbsent(pattern, k -> new LinkedHashSet<>()).add(subscriber);
    }

    public void punsubscribe(String pattern, String subscriber) {
        require(pattern, "pattern");
        require(subscriber, "订阅者");
        Set<String> subs = patterns.get(pattern);
        if (subs != null) {
            subs.remove(subscriber);
            if (subs.isEmpty()) {
                patterns.remove(pattern);
            }
        }
    }

    /** 发布：触达每条匹配订阅计一次；无订阅者即丢弃（返回 0） */
    public int publish(String channel, String message) {
        require(channel, "频道");
        if (message == null) {
            throw new IllegalArgumentException("消息不得为 null");
        }
        int reached = 0;
        Set<String> subs = channels.get(channel);
        if (subs != null) {
            for (String subscriber : subs) {
                deliveries.add(new Delivery(subscriber, channel, false, message));
                reached++;
            }
        }
        for (Map.Entry<String, Set<String>> e : patterns.entrySet()) {
            if (!globMatch(e.getKey(), channel)) {
                continue;
            }
            for (String subscriber : e.getValue()) {
                deliveries.add(new Delivery(subscriber, e.getKey(), true, message));
                reached++;
            }
        }
        return reached;
    }

    /** 订阅者收件箱（投递日志过滤视图，按投递顺序） */
    public List<Delivery> inbox(String subscriber) {
        require(subscriber, "订阅者");
        List<Delivery> out = new ArrayList<>();
        for (Delivery d : deliveries) {
            if (d.subscriber().equals(subscriber)) {
                out.add(d);
            }
        }
        return out;
    }

    public int subscriberCount(String channel) {
        Set<String> subs = channels.get(channel);
        return subs == null ? 0 : subs.size();
    }

    /** glob 匹配：* 任意串 / ? 单字符 / [seq] [a-z] [^seq] 字符集 */
    static boolean globMatch(String pattern, String text) {
        return match(pattern, 0, text, 0);
    }

    private static boolean match(String p, int pi, String t, int ti) {
        while (pi < p.length()) {
            char c = p.charAt(pi);
            if (c == '*') {
                for (int k = ti; k <= t.length(); k++) {
                    if (match(p, pi + 1, t, k)) {
                        return true;
                    }
                }
                return false;
            }
            if (c == '?') {
                if (ti >= t.length()) {
                    return false;
                }
                pi++;
                ti++;
                continue;
            }
            if (c == '[') {
                int end = p.indexOf(']', pi);
                if (end < 0 || ti >= t.length()) {
                    if (ti >= t.length()) {
                        return false;
                    }
                    if (t.charAt(ti) != c) {
                        return false;
                    }
                    pi++;
                    ti++;
                    continue;
                }
                boolean negate = pi + 1 < end && p.charAt(pi + 1) == '^';
                int s = negate ? pi + 2 : pi + 1;
                char target = t.charAt(ti);
                boolean hit = false;
                for (int i = s; i < end; i++) {
                    if (i + 2 < end && p.charAt(i + 1) == '-') {
                        if (target >= p.charAt(i) && target <= p.charAt(i + 2)) {
                            hit = true;
                        }
                        i += 2;
                    } else if (target == p.charAt(i)) {
                        hit = true;
                    }
                }
                if (negate) {
                    hit = !hit;
                }
                if (!hit) {
                    return false;
                }
                pi = end + 1;
                ti++;
                continue;
            }
            if (ti >= t.length() || t.charAt(ti) != c) {
                return false;
            }
            pi++;
            ti++;
        }
        return ti == t.length();
    }

    private static void require(String value, String what) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(what + "不得为空");
        }
    }
}
