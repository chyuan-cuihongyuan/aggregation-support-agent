package cn.chyuan.ai.domain.txkernel.service;

import java.util.List;
import java.util.Map;

/**
 * 分布式事务端口（工单 1028 EK8，seata 思想）。
 * begin·branch·commit·rollback 入口统一编排/与 mvcckernel revision 作分支版本形态只读联动（泛型形状串不 import）/
 * tx-kernel.enabled 默认关（开启才改变行为）。
 */
public interface TxPort {

    String begin(long timeoutTicks);

    Transactions.Status status(String xid);

    long registerBranch(String xid, String resourceId);

    List<Long> branches(String xid);

    /** 行键加锁：冲突立即失败 */
    boolean lockRow(String xid, String rowKey);

    /** 业务写入（AT 模式镜像登记 + 数据落定）；同值无变更返回 false（跳过） */
    boolean write(String xid, String table, String pkField, Map<String, Object> before, Map<String, Object> after);

    /** 二阶段提交：无分支短事务直接提交，有分支须全员 ack */
    void prepareCommit(String xid);

    void ackCommit(String xid, long branchId);

    /** 回滚：undo 逆序回放 + 释放锁 + 状态落定 */
    void rollback(String xid);

    /** 超时巡检：返回自动回滚的 XID */
    List<String> sweepTimeout();

    /** 时钟步进（虚拟时间，驱动超时巡检） */
    void tickTime();

    /** mvcckernel revision 形态只读联动：事务修订版本形状串（形状数据不 import mvcckernel） */
    String revisionShape(String xid);

    static TxPort inMemory() {
        return new TxHub();
    }
}
