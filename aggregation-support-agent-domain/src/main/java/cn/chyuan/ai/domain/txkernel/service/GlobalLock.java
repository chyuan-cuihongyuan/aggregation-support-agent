package cn.chyuan.ai.domain.txkernel.service;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局锁（工单 1023 EK3，seata 全局锁思想）。
 * 行键加锁/冲突立即失败不排队/提交回滚释放/同事务重入允许。
 */
public final class GlobalLock {

    private final Map<String, String> holders = new HashMap<>();

    /** 行键加锁：空闲获锁；他事务持有立即失败（返回 false）；同事务重入允许 */
    public synchronized boolean acquire(String xid, String rowKey) {
        if (xid == null || xid.isEmpty() || rowKey == null || rowKey.isEmpty()) {
            throw new IllegalArgumentException("加锁参数为空");
        }
        String holder = holders.get(rowKey);
        if (holder == null || holder.equals(xid)) {
            holders.put(rowKey, xid);
            return true;
        }
        return false;
    }

    /** 释放事务持有的全部行键锁 */
    public synchronized void release(String xid) {
        holders.values().removeIf(holder -> holder.equals(xid));
    }

    /** 行键当前持有者（空返回 null） */
    public synchronized String holder(String rowKey) {
        return holders.get(rowKey);
    }

    public synchronized int heldCount() {
        return holders.size();
    }
}
