package cn.chyuan.ai.domain.migratekernel.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 失败恢复（工单 1157 EZ7，prisma 思想）。
 * 失败条重置为 PENDING 后重试即从失败条继续（顺序应用器幂等跳过已应用）；
 * 显式 resolved 转 SKIPPED 跳过；连续失败计数留痕，重试成功清零。
 */
public final class FailureRecovery {

    private final MigrationLedger ledger;
    private final Map<String, Integer> consecutiveFailures = new LinkedHashMap<>();

    public FailureRecovery(MigrationLedger ledger) {
        this.ledger = ledger;
    }

    /** 失败留痕：状态转 FAILED 并累加连续失败计数 */
    public void recordFailure(String name) {
        MigrationLedger.Entry entry = ledger.get(name);
        entry.transition(MigrationLedger.State.FAILED);
        consecutiveFailures.merge(name, 1, Integer::sum);
    }

    /** 修复后重试：FAILED 重置 PENDING（重试从失败条继续）；非失败条拒绝 */
    public void retry(String name) {
        MigrationLedger.Entry entry = ledger.get(name);
        if (entry.state() != MigrationLedger.State.FAILED) {
            throw new IllegalStateException("非失败条不可重试: " + name);
        }
        entry.transition(MigrationLedger.State.PENDING);
    }

    /** 显式 resolved：失败条转 SKIPPED 永久跳过 */
    public void resolve(String name) {
        MigrationLedger.Entry entry = ledger.get(name);
        if (entry.state() != MigrationLedger.State.FAILED) {
            throw new IllegalStateException("非失败条不可 resolved: " + name);
        }
        entry.transition(MigrationLedger.State.SKIPPED);
    }

    /** 重试成功清计数 */
    public void recordSuccess(String name) {
        consecutiveFailures.remove(name);
    }

    public int failures(String name) {
        return consecutiveFailures.getOrDefault(name, 0);
    }
}
