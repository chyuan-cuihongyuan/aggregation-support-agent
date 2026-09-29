package cn.chyuan.ai.domain.txkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分支注册（工单 1022 EK2，seata 思想）。
 * 分支挂 XID/资源 ID 同事务唯一重复拒绝/XID 不存在拒绝。
 */
public final class Branches {

    /** 分支事务：AT 模式 */
    public static final class Branch {
        final long branchId;
        final String xid;
        final String resourceId;
        boolean done;

        Branch(long branchId, String xid, String resourceId) {
            this.branchId = branchId;
            this.xid = xid;
            this.resourceId = resourceId;
        }

        public long branchId() {
            return branchId;
        }

        public String xid() {
            return xid;
        }

        public String resourceId() {
            return resourceId;
        }
    }

    private final Transactions transactions;
    private final Map<String, List<Branch>> byXid = new LinkedHashMap<>();
    private long seq;

    public Branches(Transactions transactions) {
        this.transactions = transactions;
    }

    /** 分支注册：挂 XID；资源 ID 同事务唯一；XID 不存在拒绝；事务已终结拒绝 */
    public synchronized Branch register(String xid, String resourceId) {
        Transactions.Tx tx = transactions.get(xid);
        if (tx.status() != Transactions.Status.ACTIVE) {
            throw new IllegalStateException("事务已终结，分支注册拒绝: " + xid + "=" + tx.status());
        }
        List<Branch> branches = byXid.computeIfAbsent(xid, k -> new ArrayList<>());
        boolean duplicated = branches.stream().anyMatch(b -> b.resourceId.equals(resourceId));
        if (duplicated) {
            throw new IllegalStateException("资源 ID 同事务重复注册: " + resourceId);
        }
        Branch branch = new Branch(++seq, xid, resourceId);
        branches.add(branch);
        return branch;
    }

    public synchronized List<Branch> list(String xid) {
        transactions.get(xid);
        return new ArrayList<>(byXid.getOrDefault(xid, List.of()));
    }

    public synchronized int count(String xid) {
        return list(xid).size();
    }

    synchronized Branch require(String xid, long branchId) {
        transactions.get(xid);
        return list(xid).stream()
                .filter(b -> b.branchId == branchId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("分支未注册: " + branchId));
    }
}
