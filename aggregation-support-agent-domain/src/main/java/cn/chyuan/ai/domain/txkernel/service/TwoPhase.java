package cn.chyuan.ai.domain.txkernel.service;

import java.util.HashSet;
import java.util.Set;

/**
 * 二阶段提交（工单 1025 EK5，seata 二阶段思想）。
 * commit 校验全员注册/短事务提交/未注册分支 ack 拒绝。
 */
public final class TwoPhase {

    private final Transactions transactions;
    private final Branches branches;
    private final Set<Long> acked = new HashSet<>();

    public TwoPhase(Transactions transactions, Branches branches) {
        this.transactions = transactions;
        this.branches = branches;
    }

    /** 一阶段：进入 COMMITTING；无分支短事务直接提交；已终结事务拒绝 */
    public synchronized void prepare(String xid) {
        Transactions.Tx tx = transactions.get(xid);
        if (tx.status() == Transactions.Status.COMMITTED
                || tx.status() == Transactions.Status.ROLLBACKED) {
            throw new IllegalStateException("事务已终结: " + xid + "=" + tx.status());
        }
        if (branches.count(xid) == 0) {
            transactions.transition(xid, Transactions.Status.COMMITTED);
            return;
        }
        transactions.transition(xid, Transactions.Status.COMMITTING);
    }

    /** 二阶段分支 ack：全部 ack 即 COMMITTED；未注册分支拒绝 */
    public synchronized void ackCommit(String xid, long branchId) {
        Branches.Branch branch = branches.require(xid, branchId);
        if (transactions.status(xid) != Transactions.Status.COMMITTING) {
            throw new IllegalStateException("事务不在提交中: " + xid + "=" + transactions.status(xid));
        }
        branch.done = true;
        acked.add(branchId);
        boolean allAcked = branches.list(xid).stream().allMatch(b -> b.done);
        if (allAcked) {
            transactions.transition(xid, Transactions.Status.COMMITTED);
        }
    }

    public synchronized boolean acked(long branchId) {
        return acked.contains(branchId);
    }
}
