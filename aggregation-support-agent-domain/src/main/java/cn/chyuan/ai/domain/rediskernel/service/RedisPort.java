package cn.chyuan.ai.domain.rediskernel.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Redis 端口+组合管线（工单 0654 BY8）。
 * 命令入口（SET/GET/DEL/EXPIRE/ZADD/ZRANGEBYSCORE/PUBLISH/SUBSCRIBE）统一编排
 * （SDS 存串/字典存储/跳表 ZSet/过期/淘汰/发布订阅全组件串联）+RDB 式快照导出；
 * 与 storekernel 只读联动（快照字节作段块输入形态，泛型入参不 import storekernel，
 * 不改 storekernel 任何类）/ redis-kernel.enabled 默认关（开启才改变行为）。
 */
public interface RedisPort {

    enum Status { OK, ERR }

    /** 命令回包：状态/错误/字符串值/列表值/整数值 */
    record Reply(Status status, String error, byte[] str, List<String> list, Long num) {
        static Reply ok(byte[] str, List<String> list, Long num) {
            return new Reply(Status.OK, null, str, list, num);
        }

        static Reply err(String error) {
            return new Reply(Status.ERR, error, null, null, null);
        }
    }

    Reply set(String key, byte[] value);

    Reply get(String key);

    Reply del(String... keys);

    /** 设置毫秒 TTL；键不存在返回 0 */
    Reply expire(String key, long ttlMs);

    Reply zadd(String key, double score, String member);

    Reply zrangeByScore(String key, double min, double max);

    Reply publish(String channel, String message);

    Reply subscribe(String channel, String subscriber);

    Reply psubscribe(String pattern, String subscriber);

    /** RDB 式快照导出（跳过已过期键），登记统计 */
    byte[] dump();

    /** 快照载入（整库替换），返回载入键数 */
    int load(byte[] snapshot);

    /**
     * 与 storekernel 只读联动：快照内容输出为段行形态（键/类型/规模），
     * 作为磁盘存储段块的输入形态——只读形状数据，不 import storekernel。
     */
    List<String> snapshotAsSegmentRows();

    long memoryUsage();

    int keyCount();

    List<PubSub.Delivery> inbox(String subscriber);

    Snapshot.Stats snapshotStats();

    /** 测试/演示用时间推进 */
    void advanceTime(long deltaMs);

    /** 配置淘汰策略与内存上限（0=不设限）——配置驱动的能力开关形态 */
    void configure(Eviction.Policy policy, long maxMemoryBytes);

    /** 内存假实现：SDS/Dict/ZSet/Expires/Eviction/PubSub/Snapshot 全链 */
    class InMemoryRedis implements RedisPort {

        private static final long KEY_OVERHEAD = 64L;

        private final Dict<Object> store = new Dict<>();
        private final Expires expires = new Expires();
        private final Eviction eviction;
        private final PubSub pubsub = new PubSub();
        private final Snapshot.Stats stats = new Snapshot.Stats();
        private final Random random;
        private long nowMs;

        public InMemoryRedis() {
            this(new Random());
        }

        public InMemoryRedis(Random random) {
            if (random == null) {
                throw new IllegalArgumentException("随机源不得为 null");
            }
            this.random = random;
            this.eviction = new Eviction(random);
        }

        @Override
        public synchronized Reply set(String key, byte[] value) {
            requireKey(key);
            if (value == null) {
                return Reply.err("SET 值不得为 null");
            }
            expires.clear(key);
            long cost = key.length() + KEY_OVERHEAD + value.length;
            if (!ensureRoom(key, cost)) {
                return Reply.err("OOM command not allowed when used memory > 'maxmemory'");
            }
            Sds sds = Sds.wrap(value);
            store.put(key, sds.bytes());
            eviction.register(key, cost, nowMs, false);
            return Reply.ok(null, null, 1L);
        }

        @Override
        public synchronized Reply get(String key) {
            requireKey(key);
            if (lazyExpire(key)) {
                return Reply.ok(null, null, null);
            }
            Object value = store.get(key);
            if (value == null) {
                return Reply.ok(null, null, null);
            }
            if (!(value instanceof byte[] bytes)) {
                return Reply.err("WRONGTYPE Operation against a key holding the wrong kind of value");
            }
            eviction.touch(key, nowMs);
            return Reply.ok(bytes, null, null);
        }

        @Override
        public synchronized Reply del(String... keys) {
            if (keys == null) {
                return Reply.err("DEL 键清单不得为 null");
            }
            long removed = 0;
            for (String key : keys) {
                requireKey(key);
                lazyExpire(key);
                if (store.remove(key) != null) {
                    eviction.unregister(key);
                    removed++;
                }
            }
            return Reply.ok(null, null, removed);
        }

        @Override
        public synchronized Reply expire(String key, long ttlMs) {
            requireKey(key);
            if (ttlMs <= 0) {
                return Reply.err("TTL 必须为正毫秒");
            }
            if (lazyExpire(key) || store.get(key) == null) {
                return Reply.ok(null, null, 0L);
            }
            expires.set(key, nowMs + ttlMs);
            eviction.setVolatile(key, true);
            return Reply.ok(null, null, 1L);
        }

        @Override
        public synchronized Reply zadd(String key, double score, String member) {
            requireKey(key);
            if (member == null || member.isEmpty()) {
                return Reply.err("ZADD 成员不得为空");
            }
            lazyExpire(key);
            Object existing = store.get(key);
            if (existing instanceof byte[]) {
                return Reply.err("WRONGTYPE Operation against a key holding the wrong kind of value");
            }
            ZSet zset = existing instanceof ZSet z ? z : new ZSet(random);
            zset.add(score, member);
            long cost = key.length() + KEY_OVERHEAD + zset.size() * 32L;
            if (!ensureRoom(key, cost)) {
                return Reply.err("OOM command not allowed when used memory > 'maxmemory'");
            }
            store.put(key, zset);
            eviction.register(key, cost, nowMs, expires.at(key) != null);
            return Reply.ok(null, null, 1L);
        }

        @Override
        public synchronized Reply zrangeByScore(String key, double min, double max) {
            requireKey(key);
            if (lazyExpire(key)) {
                return Reply.ok(null, new ArrayList<>(), null);
            }
            Object value = store.get(key);
            if (value == null) {
                return Reply.ok(null, new ArrayList<>(), null);
            }
            if (!(value instanceof ZSet zset)) {
                return Reply.err("WRONGTYPE Operation against a key holding the wrong kind of value");
            }
            eviction.touch(key, nowMs);
            return Reply.ok(null, zset.rangeByScore(min, max), null);
        }

        @Override
        public synchronized Reply publish(String channel, String message) {
            return Reply.ok(null, null, (long) pubsub.publish(channel, message));
        }

        @Override
        public synchronized Reply subscribe(String channel, String subscriber) {
            pubsub.subscribe(channel, subscriber);
            return Reply.ok(null, null, null);
        }

        @Override
        public synchronized Reply psubscribe(String pattern, String subscriber) {
            pubsub.psubscribe(pattern, subscriber);
            return Reply.ok(null, null, null);
        }

        @Override
        public synchronized byte[] dump() {
            long start = System.nanoTime();
            List<Snapshot.Entry> entries = new ArrayList<>();
            for (String key : store.keys()) {
                if (expires.isExpired(key, nowMs)) {
                    continue;
                }
                Object value = store.get(key);
                if (value instanceof byte[] bytes) {
                    entries.add(new Snapshot.Entry(key, new Snapshot.Str(bytes)));
                } else if (value instanceof ZSet zset) {
                    List<Snapshot.MemberScore> items = new ArrayList<>();
                    for (String member : zset.members()) {
                        items.add(new Snapshot.MemberScore(member, zset.score(member)));
                    }
                    entries.add(new Snapshot.Entry(key, new Snapshot.Zset(items)));
                }
            }
            byte[] snap = Snapshot.dump(entries);
            long costMs = Math.max(0L, (System.nanoTime() - start) / 1_000_000L);
            stats.record("manual", entries.size(), snap.length, costMs, nowMs);
            return snap;
        }

        @Override
        public synchronized int load(byte[] snapshot) {
            List<Snapshot.Entry> entries = Snapshot.load(snapshot);
            for (String key : store.keys()) {
                store.remove(key);
                eviction.unregister(key);
            }
            expires.clearAll();
            for (Snapshot.Entry entry : entries) {
                if (entry.value() instanceof Snapshot.Str str) {
                    byte[] data = str.data().clone();
                    store.put(entry.key(), data);
                    eviction.register(entry.key(), entry.key().length() + KEY_OVERHEAD + data.length, nowMs, false);
                } else if (entry.value() instanceof Snapshot.Zset zset) {
                    ZSet z = new ZSet(random);
                    for (Snapshot.MemberScore item : zset.items()) {
                        z.add(item.score(), item.member());
                    }
                    store.put(entry.key(), z);
                    eviction.register(entry.key(), entry.key().length() + KEY_OVERHEAD + z.size() * 32L, nowMs, false);
                }
            }
            return entries.size();
        }

        @Override
        public synchronized List<String> snapshotAsSegmentRows() {
            List<String> rows = new ArrayList<>();
            for (String key : store.keys()) {
                if (expires.isExpired(key, nowMs)) {
                    continue;
                }
                Object value = store.get(key);
                if (value instanceof byte[] bytes) {
                    rows.add(key + "\tSTR\t" + bytes.length);
                } else if (value instanceof ZSet zset) {
                    rows.add(key + "\tZSET\t" + zset.size());
                }
            }
            return rows;
        }

        @Override
        public synchronized long memoryUsage() {
            return eviction.usedBytes();
        }

        @Override
        public synchronized int keyCount() {
            return store.size();
        }

        @Override
        public synchronized List<PubSub.Delivery> inbox(String subscriber) {
            return pubsub.inbox(subscriber);
        }

        @Override
        public synchronized Snapshot.Stats snapshotStats() {
            return stats;
        }

        @Override
        public synchronized void advanceTime(long deltaMs) {
            if (deltaMs < 0) {
                throw new IllegalArgumentException("时间推进量不得为负");
            }
            nowMs += deltaMs;
        }

        @Override
        public synchronized void configure(Eviction.Policy policy, long maxMemoryBytes) {
            eviction.setPolicy(policy);
            eviction.setMaxMemory(maxMemoryBytes);
        }

        /** 腾挪空间：按新值成本与既有成本增量判定，必要时按策略逐键淘汰 */
        private boolean ensureRoom(String key, long incomingCost) {
            while (true) {
                Eviction.Meta existing = eviction.meta(key);
                long delta = existing != null ? incomingCost - existing.sizeBytes : incomingCost;
                if (delta <= 0 || eviction.admits(delta)) {
                    return true;
                }
                if (eviction.policy() == Eviction.Policy.NO_EVICTION) {
                    return false;
                }
                String victim = eviction.evictOne(nowMs);
                if (victim == null) {
                    return false;
                }
                store.remove(victim);
                expires.clear(victim);
            }
        }

        private boolean lazyExpire(String key) {
            if (expires.lazyExpire(key, nowMs)) {
                store.remove(key);
                eviction.unregister(key);
                return true;
            }
            return false;
        }

        private List<String> evictionKeys() {
            List<String> keys = new ArrayList<>();
            for (String key : store.keys()) {
                keys.add(key);
            }
            return keys;
        }

        private static void requireKey(String key) {
            if (key == null || key.isEmpty()) {
                throw new IllegalArgumentException("键不得为空");
            }
        }
    }
}
