package cn.chyuan.ai.domain.natskernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订阅树（工单 0945 EB2 / 0946 EB3，nats-server 思想）。
 * trie 注册与移除/重复注册幂等/节点计数；通配符 `*` 单层、`>` 多层尾贪心；字面优先投递序。
 */
public final class SubTree {

    /** 一条订阅：连接 + 订阅模式 + 可选队列组 */
    public static final class Sub {
        public final String conn;
        public final String pattern;
        public final String group;

        Sub(String conn, String pattern, String group) {
            this.conn = conn;
            this.pattern = pattern;
            this.group = group;
        }
    }

    static final class Node {
        final Map<String, Node> children = new LinkedHashMap<>();
        final List<Sub> subs = new ArrayList<>();
    }

    private final Node root = new Node();
    private int nodeCount = 0;

    /** 注册订阅：词法校验、同连接同模式同组幂等 */
    public void subscribe(String conn, String pattern, String group) {
        String[] tokens = Subjects.parse(pattern);
        Node node = walk(tokens, true);
        for (Sub existing : node.subs) {
            if (existing.conn.equals(conn) && existing.group != null && existing.group.equals(group)) {
                return;
            }
            if (existing.conn.equals(conn) && existing.group == null && group == null) {
                return;
            }
        }
        node.subs.add(new Sub(conn, pattern, group));
    }

    /** 取消订阅：移除该连接在该模式上的订阅（组内成员随移除） */
    public boolean unsubscribe(String conn, String pattern) {
        Node node = walk(Subjects.parse(pattern), false);
        if (node == null) {
            return false;
        }
        boolean removed = node.subs.removeIf(s -> s.conn.equals(conn));
        return removed;
    }

    /** 移除连接全部订阅（保活踢除用） */
    public void unsubscribeAll(String conn) {
        removeAll(root, conn);
    }

    /** trie 节点计数 */
    public int nodeCount() {
        return nodeCount;
    }

    /** 是否存在可投递订阅（no_responders 探测） */
    public int responders(String subject) {
        return matchTargets(subject).size();
    }

    /** 匹配投递序：字面模式订阅在前（插入序），通配模式订阅在后（插入序） */
    public List<Sub> matchTargets(String subject) {
        String[] tokens = Subjects.parse(subject);
        List<Sub> literals = new ArrayList<>();
        List<Sub> wildcards = new ArrayList<>();
        collect(root, tokens, 0, literals, wildcards);
        List<Sub> all = new ArrayList<>(literals);
        all.addAll(wildcards);
        return all;
    }

    /** 通配符匹配语义：`*` 恰一层，`>` 居尾贪心至少一层 */
    public static boolean matches(String[] pattern, String[] subject) {
        int i = 0;
        for (String token : pattern) {
            if (token.equals(">")) {
                return i < subject.length;
            }
            if (i >= subject.length) {
                return false;
            }
            if (!token.equals("*") && !token.equals(subject[i])) {
                return false;
            }
            i++;
        }
        return i == subject.length;
    }

    private void collect(Node node, String[] subject, int depth, List<Sub> literals, List<Sub> wildcards) {
        if (depth == subject.length) {
            for (Sub sub : node.subs) {
                if (Subjects.isLiteral(sub.pattern)) {
                    literals.add(sub);
                } else {
                    wildcards.add(sub);
                }
            }
            return;
        }
        Node child = node.children.get(subject[depth]);
        if (child != null) {
            collect(child, subject, depth + 1, literals, wildcards);
        }
        Node star = node.children.get("*");
        if (star != null) {
            collect(star, subject, depth + 1, literals, wildcards);
        }
        Node gt = node.children.get(">");
        if (gt != null) {
            for (Sub sub : gt.subs) {
                wildcards.add(sub);
            }
        }
    }

    private Node walk(String[] tokens, boolean create) {
        Node node = root;
        for (String token : tokens) {
            Node child = node.children.get(token);
            if (child == null) {
                if (!create) {
                    return null;
                }
                child = new Node();
                node.children.put(token, child);
                nodeCount++;
            }
            node = child;
        }
        return node;
    }

    private void removeAll(Node node, String conn) {
        node.subs.removeIf(s -> s.conn.equals(conn));
        for (Node child : node.children.values()) {
            removeAll(child, conn);
        }
    }
}
