package cn.chyuan.ai.domain.txkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 超时回滚（工单 1027 EK7，seata 超时巡检思想）。
 * 到期检查/超时自动回滚/未超时不动。
 */
public final class TimeoutRollback {

    /** 巡检回调：对超时事务执行回滚 */
    public interface Roller {
        void rollback(String xid);
    }

    private final Transactions transactions;
    private final Roller roller;

    public TimeoutRollback(Transactions transactions, Roller roller) {
        this.transactions = transactions;
        this.roller = roller;
    }

    /** 巡检：deadline 已到且仍 ACTIVE/COMMITTING 的事务自动回滚；未超时不动 */
    public synchronized List<String> sweep() {
        List<String> rolled = new ArrayList<>();
        for (Transactions.Tx tx : activeTransactions()) {
            if (transactions.now() >= tx.deadline()) {
                roller.rollback(tx.xid());
                rolled.add(tx.xid());
            }
        }
        return rolled;
    }

    private synchronized List<Transactions.Tx> activeTransactions() {
        return transactions.snapshot().stream()
                .filter(t -> t.status() == Transactions.Status.ACTIVE
                        || t.status() == Transactions.Status.COMMITTING)
                .toList();
    }
}
