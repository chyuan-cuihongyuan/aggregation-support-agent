package cn.chyuan.ai.domain.txkernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分布式事务编排实现（工单 1028 EK8，seata 思想）。
 * 组合全局事务/分支注册/全局锁/undo log/二阶段/逆序回放/超时巡检；
 * mvcckernel revision 形态只读联动：事务级修订版本计数形状串（形状数据不 import mvcckernel）。
 */
public final class TxHub implements TxPort {

    private final Transactions transactions = new Transactions();
    private final Branches branches = new Branches(transactions);
    private final GlobalLock locks = new GlobalLock();
    private final UndoReplay replay = new UndoReplay();
    private final TwoPhase twoPhase = new TwoPhase(transactions, branches);
    private final TimeoutRollback sweeper = new TimeoutRollback(transactions, this::rollback);
    private final Map<String, Long> revisions = new HashMap<>();
    private long revisionSeq;

    @Override
    public String begin(long timeoutTicks) {
        return transactions.begin(timeoutTicks).xid();
    }

    @Override
    public Transactions.Status status(String xid) {
        return transactions.status(xid);
    }

    @Override
    public long registerBranch(String xid, String resourceId) {
        return branches.register(xid, resourceId).branchId();
    }

    @Override
    public List<Long> branches(String xid) {
        return branches.list(xid).stream().map(Branches.Branch::branchId).toList();
    }

    @Override
    public boolean lockRow(String xid, String rowKey) {
        transactions.get(xid);
        return locks.acquire(xid, rowKey);
    }

    @Override
    public boolean write(String xid, String table, String pkField,
                         Map<String, Object> before, Map<String, Object> after) {
        transactions.get(xid);
        UndoLog.RowImage image = UndoLog.build(xid, table, pkField, before, after);
        if (image == null) {
            return false;
        }
        replay.write(image);
        revisionSeq++;
        revisions.merge(xid, 1L, Long::sum);
        return true;
    }

    @Override
    public void prepareCommit(String xid) {
        twoPhase.prepare(xid);
    }

    @Override
    public void ackCommit(String xid, long branchId) {
        twoPhase.ackCommit(xid, branchId);
        if (transactions.status(xid) == Transactions.Status.COMMITTED) {
            locks.release(xid);
        }
    }

    @Override
    public void rollback(String xid) {
        Transactions.Tx tx = transactions.get(xid);
        if (tx.status() == Transactions.Status.COMMITTED) {
            throw new IllegalStateException("已提交事务不可回滚: " + xid);
        }
        replay.replay(xid);
        locks.release(xid);
        transactions.transition(xid, Transactions.Status.ROLLBACKED);
    }

    @Override
    public List<String> sweepTimeout() {
        return sweeper.sweep();
    }

    @Override
    public void tickTime() {
        transactions.tick();
    }

    @Override
    public String revisionShape(String xid) {
        transactions.get(xid);
        long revision = revisions.getOrDefault(xid, 0L);
        return "xid:" + xid + "@rev=" + revision;
    }
}
