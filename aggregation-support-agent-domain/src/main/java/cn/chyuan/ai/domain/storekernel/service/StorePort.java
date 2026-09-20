package cn.chyuan.ai.domain.storekernel.service;

import java.util.List;

/**
 * 存储端口（工单 0479 BE8）。
 * put/get/scan/compact 四能力面 + 批量写 + 统计快照；
 * 内存 LSM 引擎假实现。store-kernel.enabled 默认关（0462-D8），
 * 不接 MyBatis 链路；持久化面 = 第 30 表 store_segment。
 */
public interface StorePort {

    /** 写入键值，返回序列号 */
    long put(String key, String value);

    /** 删除（墓碑），返回序列号 */
    long delete(String key);

    /** 批量写入，返回各序列号 */
    List<Long> batchPut(List<String[]> pairs);

    /** 读当前值（不存在返回 null） */
    String get(String key);

    /** 快照一致读 */
    String getAt(String key, long snapshotSequence);

    /** 前缀扫描 */
    List<SstSegment.Row> scanPrefix(String prefix);

    /** 统计快照 */
    LsmEngine.Stats stats();

    /** 内存 LSM 假实现 */
    class InMemoryLsmStore implements StorePort {

        private final LsmEngine engine;

        public InMemoryLsmStore() {
            this(3, 2);
        }

        public InMemoryLsmStore(int maxLevel, int l0CompactionTrigger) {
            this.engine = new LsmEngine(maxLevel, l0CompactionTrigger);
        }

        @Override
        public long put(String key, String value) {
            return engine.put(key, value);
        }

        @Override
        public long delete(String key) {
            return engine.delete(key);
        }

        @Override
        public List<Long> batchPut(List<String[]> pairs) {
            return engine.batchPut(pairs);
        }

        @Override
        public String get(String key) {
            return engine.get(key);
        }

        @Override
        public String getAt(String key, long snapshotSequence) {
            return engine.getAt(key, snapshotSequence);
        }

        @Override
        public List<SstSegment.Row> scanPrefix(String prefix) {
            return engine.scanPrefix(prefix);
        }

        @Override
        public LsmEngine.Stats stats() {
            return engine.stats();
        }
    }
}
