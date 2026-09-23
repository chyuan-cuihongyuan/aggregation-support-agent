package cn.chyuan.ai.domain.rediskernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 跳表 ZSet（工单 0649 BY3，redis 思想）。
 * 概率多层层级（随机种子可注入，确定性可测）/score+member 双键定序/
 * 排名（0 基）与按分数段范围查询（含端点）/重复 member 更新分数/
 * NaN 分数与空 member 拒绝。
 */
public final class ZSet {

    public static final int MAX_LEVEL = 32;
    private static final double P = 0.25d;

    private static final class Node {
        final String member;
        final double score;
        final Node[] forward;
        final long[] span;
        Node backward;

        Node(String member, double score, int level) {
            this.member = member;
            this.score = score;
            this.forward = new Node[level];
            this.span = new long[level];
        }
    }

    private final Node head = new Node(null, 0, MAX_LEVEL);
    private final Random random;
    private final Map<String, Double> scores = new HashMap<>();
    private Node tail;
    private int level = 1;
    private int length;

    public ZSet() {
        this(new Random());
    }

    public ZSet(Random random) {
        if (random == null) {
            throw new IllegalArgumentException("随机源不得为 null");
        }
        this.random = random;
    }

    public int size() {
        return length;
    }

    public Double score(String member) {
        requireMember(member);
        return scores.get(member);
    }

    /** 新增或更新（重复 member 删旧插新，保持定序不变量） */
    public void add(double score, String member) {
        requireMember(member);
        if (Double.isNaN(score)) {
            throw new IllegalArgumentException("分数不得为 NaN");
        }
        if (scores.containsKey(member)) {
            delete(scores.get(member), member);
        }
        insert(score, member);
        scores.put(member, score);
    }

    /** 排名（0 基）；不存在返回 -1 */
    public long rank(String member) {
        requireMember(member);
        Double s = scores.get(member);
        if (s == null) {
            return -1L;
        }
        double score = s;
        long rank = 0;
        Node x = head;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null
                    && (x.forward[i].score < score
                        || (x.forward[i].score == score && x.forward[i].member.compareTo(member) <= 0))) {
                rank += x.span[i];
                x = x.forward[i];
            }
            if (x != head && x.member.equals(member)) {
                return rank - 1;
            }
        }
        return -1L;
    }

    /** 分数段范围（升序，含端点） */
    public List<String> rangeByScore(double min, double max) {
        if (min > max) {
            throw new IllegalArgumentException("分数段下界不得大于上界");
        }
        List<String> out = new ArrayList<>();
        Node x = head;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && x.forward[i].score < min) {
                x = x.forward[i];
            }
        }
        x = x.forward[0];
        while (x != null && x.score <= max) {
            out.add(x.member);
            x = x.forward[0];
        }
        return out;
    }

    /** 升序全量成员 */
    public List<String> members() {
        return rangeByScore(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    private void insert(double score, String member) {
        Node[] update = new Node[MAX_LEVEL];
        long[] rank = new long[MAX_LEVEL];
        Node x = head;
        for (int i = level - 1; i >= 0; i--) {
            rank[i] = (i == level - 1) ? 0 : rank[i + 1];
            while (x.forward[i] != null
                    && (x.forward[i].score < score
                        || (x.forward[i].score == score && x.forward[i].member.compareTo(member) < 0))) {
                rank[i] += x.span[i];
                x = x.forward[i];
            }
            update[i] = x;
        }
        int lvl = randomLevel();
        if (lvl > level) {
            for (int i = level; i < lvl; i++) {
                rank[i] = 0;
                update[i] = head;
                update[i].span[i] = length;
            }
            level = lvl;
        }
        x = new Node(member, score, lvl);
        for (int i = 0; i < lvl; i++) {
            x.forward[i] = update[i].forward[i];
            update[i].forward[i] = x;
            x.span[i] = update[i].span[i] - (rank[0] - rank[i]);
            update[i].span[i] = (rank[0] - rank[i]) + 1;
        }
        for (int i = lvl; i < level; i++) {
            update[i].span[i]++;
        }
        x.backward = (update[0] == head) ? null : update[0];
        if (x.forward[0] != null) {
            x.forward[0].backward = x;
        } else {
            tail = x;
        }
        length++;
    }

    private void delete(double score, String member) {
        Node[] update = new Node[MAX_LEVEL];
        Node x = head;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null
                    && (x.forward[i].score < score
                        || (x.forward[i].score == score && x.forward[i].member.compareTo(member) < 0))) {
                x = x.forward[i];
            }
            update[i] = x;
        }
        x = x.forward[0];
        if (x == null || !x.member.equals(member)) {
            return;
        }
        for (int i = 0; i < level; i++) {
            if (update[i].forward[i] == x) {
                update[i].span[i] += x.span[i] - 1;
                update[i].forward[i] = x.forward[i];
            } else {
                update[i].span[i]--;
            }
        }
        if (x.forward[0] != null) {
            x.forward[0].backward = x.backward;
        } else {
            tail = x.backward;
        }
        while (level > 1 && head.forward[level - 1] == null) {
            level--;
        }
        length--;
        scores.remove(member);
    }

    private int randomLevel() {
        int lvl = 1;
        while (lvl < MAX_LEVEL && random.nextDouble() < P) {
            lvl++;
        }
        return lvl;
    }

    private void requireMember(String member) {
        if (member == null || member.isEmpty()) {
            throw new IllegalArgumentException("ZSet 成员不得为空");
        }
    }
}
