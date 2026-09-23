package cn.chyuan.ai.domain.rediskernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 字典渐进式 rehash（工单 0648 BY2，redis 思想）。
 * 双 ht 表/负载因子 ≥1 触发扩容、<0.1 触发收缩/桶级步进迁移（step）/
 * 迁移中读写双表兼容（新增只进 t1，查找先 t1 后 t0）/迁移完成收缩单表。
 */
public final class Dict<V> {

    private static final int MIN_SIZE = 4;

    private Node<V>[] t0;
    private Node<V>[] t1;
    private int used0;
    private int used1;
    private int size0;
    private int size1;
    /** -1 = 未在 rehash；否则为 t0 中下一个待迁移桶下标 */
    private int rehashIdx = -1;

    private static final class Node<V> {
        final String key;
        V value;
        Node<V> next;

        Node(String key, V value, Node<V> next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    @SuppressWarnings("unchecked")
    public Dict() {
        t0 = new Node[MIN_SIZE];
        size0 = MIN_SIZE;
    }

    public boolean isRehashing() {
        return rehashIdx != -1;
    }

    public int size() {
        return used0 + used1;
    }

    public V get(String key) {
        requireKey(key);
        if (isRehashing()) {
            V v = tableGet(t1, size1, key);
            if (v != null) {
                return v;
            }
        }
        return tableGet(t0, size0, key);
    }

    /** 新增/覆盖；返回旧值（无旧值返回 null）；迁移中每次写顺带推进一步 */
    public V put(String key, V value) {
        requireKey(key);
        if (value == null) {
            throw new IllegalArgumentException("字典值不得为 null");
        }
        if (isRehashing()) {
            step();
        }
        V old = tableGet(t0, size0, key);
        if (old == null && isRehashing()) {
            old = tableGet(t1, size1, key);
        }
        if (old != null) {
            tablePutValue(key, value);
            return old;
        }
        if (isRehashing()) {
            int b = bucket(key, size1);
            t1[b] = new Node<>(key, value, t1[b]);
            used1++;
        } else {
            int b = bucket(key, size0);
            t0[b] = new Node<>(key, value, t0[b]);
            used0++;
            if (used0 >= size0) {
                expand();
            }
        }
        return null;
    }

    public V remove(String key) {
        requireKey(key);
        if (isRehashing()) {
            step();
        }
        V removed = tableRemove(t0, size0, key, 0);
        if (removed == null && isRehashing()) {
            removed = tableRemove(t1, size1, key, 1);
        }
        if (removed != null && !isRehashing() && size0 > MIN_SIZE && used0 < size0 * 0.1d) {
            shrink();
        }
        return removed;
    }

    /** 渐进迁移一步：搬运 t0 中下一个非空桶（跳过空桶不计数为有效步） */
    public void step() {
        if (!isRehashing()) {
            return;
        }
        while (rehashIdx < size0) {
            Node<V> bucket = t0[rehashIdx];
            if (bucket == null) {
                rehashIdx++;
                continue;
            }
            while (bucket != null) {
                int b = bucket(bucket.key, size1);
                t1[b] = new Node<>(bucket.key, bucket.value, t1[b]);
                used1++;
                used0--;
                bucket = bucket.next;
            }
            t0[rehashIdx] = null;
            rehashIdx++;
            return;
        }
        finishRehash();
    }

    /** 最多推进 maxSteps 步；返回是否已完成迁移 */
    public boolean stepUntilDone(int maxSteps) {
        if (maxSteps < 0) {
            throw new IllegalArgumentException("步数上限不得为负");
        }
        for (int i = 0; i < maxSteps && isRehashing(); i++) {
            step();
        }
        return !isRehashing();
    }

    /** 键清单（t0 桶序+链序在前，迁移中追加 t1） */
    public List<String> keys() {
        List<String> out = new ArrayList<>();
        collect(t0, size0, out);
        if (isRehashing()) {
            collect(t1, size1, out);
        }
        return out;
    }

    private void collect(Node<V>[] table, int size, List<String> out) {
        for (int i = 0; i < size; i++) {
            Node<V> n = table[i];
            while (n != null) {
                out.add(n.key);
                n = n.next;
            }
        }
    }

    private V tableGet(Node<V>[] table, int size, String key) {
        Node<V> n = table[bucket(key, size)];
        while (n != null) {
            if (n.key.equals(key)) {
                return n.value;
            }
            n = n.next;
        }
        return null;
    }

    private void tablePutValue(String key, V value) {
        if (findAndSet(t0, size0, key, value)) {
            return;
        }
        if (isRehashing()) {
            findAndSet(t1, size1, key, value);
        }
    }

    private boolean findAndSet(Node<V>[] table, int size, String key, V value) {
        Node<V> n = table[bucket(key, size)];
        while (n != null) {
            if (n.key.equals(key)) {
                n.value = value;
                return true;
            }
            n = n.next;
        }
        return false;
    }

    private V tableRemove(Node<V>[] table, int size, String key, int which) {
        int b = bucket(key, size);
        Node<V> n = table[b];
        Node<V> prev = null;
        while (n != null) {
            if (n.key.equals(key)) {
                V v = n.value;
                if (prev == null) {
                    table[b] = n.next;
                } else {
                    prev.next = n.next;
                }
                if (which == 1) {
                    used1--;
                } else {
                    used0--;
                }
                return v;
            }
            prev = n;
            n = n.next;
        }
        return null;
    }

    private void expand() {
        resize(nextPower(used0 * 2));
    }

    private void shrink() {
        resize(nextPower(Math.max(used0, MIN_SIZE)));
    }

    @SuppressWarnings("unchecked")
    private void resize(int newSize) {
        t1 = new Node[newSize];
        size1 = newSize;
        used1 = 0;
        rehashIdx = 0;
    }

    private void finishRehash() {
        t0 = t1;
        size0 = size1;
        used0 = used1;
        t1 = null;
        size1 = 0;
        used1 = 0;
        rehashIdx = -1;
    }

    private void requireKey(String key) {
        if (key == null) {
            throw new IllegalArgumentException("字典键不得为 null");
        }
    }

    private static int bucket(String key, int size) {
        return (key.hashCode() & 0x7fffffff) & (size - 1);
    }

    private static int nextPower(int n) {
        int p = MIN_SIZE;
        while (p < n) {
            p <<= 1;
        }
        return p;
    }
}
