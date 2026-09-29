package cn.chyuan.ai.domain.txkernel.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局事务发起（工单 1021 EK1，seata 思想）。
 * XID 生成唯一/超时可配注册/重复 begin 独立新事务。
 */
public final class Transactions {

    /** 全局事务状态机：ACTIVE → COMMITTING → COMMITTED / ROLLBACKED */
    public enum Status {
        ACTIVE, COMMITTING, COMMITTED, ROLLBACKED
    }

    /** 全局事务 */
    public static final class Tx {
        final String xid;
        final long timeoutTicks;
        long bornTick;
        Status status = Status.ACTIVE;

        Tx(String xid, long timeoutTicks, long bornTick) {
            this.xid = xid;
            this.timeoutTicks = timeoutTicks;
            this.bornTick = bornTick;
        }

        public String xid() {
            return xid;
        }

        public Status status() {
            return status;
        }

        public long deadline() {
            return bornTick + timeoutTicks;
        }
    }

    private final Map<String, Tx> table = new LinkedHashMap<>();
    private long seq;
    private long now;

    /** 发起全局事务：XID 唯一；超时可配（须为正）；重复 begin 产生独立新事务 */
    public synchronized Tx begin(long timeoutTicks) {
        if (timeoutTicks <= 0) {
            throw new IllegalArgumentException("超时须为正: " + timeoutTicks);
        }
        String xid = "gid-" + (++seq);
        Tx tx = new Tx(xid, timeoutTicks, now);
        table.put(xid, tx);
        return tx;
    }

    public synchronized Tx get(String xid) {
        Tx tx = table.get(xid);
        if (tx == null) {
            throw new IllegalArgumentException("全局事务不存在: " + xid);
        }
        return tx;
    }

    public synchronized Status status(String xid) {
        return get(xid).status;
    }

    synchronized void transition(String xid, Status target) {
        get(xid).status = target;
    }

    public synchronized int count() {
        return table.size();
    }

    /** 事务表快照（巡检用只读视图） */
    public synchronized java.util.List<Tx> snapshot() {
        return new java.util.ArrayList<>(table.values());
    }

    synchronized void tick() {
        now++;
    }

    synchronized long now() {
        return now;
    }
}
