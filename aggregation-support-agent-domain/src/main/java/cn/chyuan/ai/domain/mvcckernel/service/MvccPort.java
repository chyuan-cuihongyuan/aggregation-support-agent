package cn.chyuan.ai.domain.mvcckernel.service;

import java.util.List;
import java.util.Map;

/**
 * MVCC 端口（工单 0922 DD8，etcd MVCC 思想）。
 * put·get·watch·compact 入口统一编排/与 rediskernel 键值形态只读联动（泛型 Map 不 import）/
 * mvcc-kernel.enabled 默认关（开启才改变行为）。
 */
public interface MvccPort {

    MvccStore store();

    long put(String key, String value);

    String get(String key);

    String getAt(String key, long revision);

    List<MvccStore.Event> watch(long fromRevision);

    int compact(long revision);

    boolean txn(List<String[]> compares, List<String[]> puts);

    /** rediskernel 只读联动形态：键值形状批量写入（形状数据不 import rediskernel） */
    long ingestKeyValue(Map<String, String> keyValues);

    static MvccPort inMemory() {
        return new InMemoryMvcc();
    }
}

final class InMemoryMvcc implements MvccPort {

    private final MvccStore store = new MvccStore();

    @Override
    public MvccStore store() {
        return store;
    }

    @Override
    public long put(String key, String value) {
        return store.put(key, value);
    }

    @Override
    public String get(String key) {
        return store.get(key);
    }

    @Override
    public String getAt(String key, long revision) {
        return store.getAt(key, revision);
    }

    @Override
    public List<MvccStore.Event> watch(long fromRevision) {
        return store.watch(fromRevision);
    }

    @Override
    public int compact(long revision) {
        return store.compact(revision);
    }

    @Override
    public boolean txn(List<String[]> compares, List<String[]> puts) {
        return store.txn(compares, puts);
    }

    @Override
    public long ingestKeyValue(Map<String, String> keyValues) {
        long last = -1;
        for (Map.Entry<String, String> e : keyValues.entrySet()) {
            last = store.put(e.getKey(), e.getValue());
        }
        return last;
    }
}
