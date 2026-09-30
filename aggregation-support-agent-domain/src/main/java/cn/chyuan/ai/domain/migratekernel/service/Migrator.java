package cn.chyuan.ai.domain.migratekernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 迁移编排实现（工单 1158 EZ8，prisma 思想）。
 * plan 列未应用；apply 顺序推进并联动恢复计数；失败即停留状态；
 * verify 汇聚 checksum 篡改检出与 drift 比对。
 */
public final class Migrator implements MigratePort {

    private final MigrationLedger ledger = new MigrationLedger();
    private final Checksums checksums = new Checksums();
    private final OrderedApplier applier = new OrderedApplier(ledger, checksums);
    private final Baselines baselines = new Baselines();
    private final FailureRecovery recovery = new FailureRecovery(ledger);

    @Override
    public void record(String name, String content) {
        ledger.record(name, content);
    }

    @Override
    public List<String> plan() {
        List<String> pending = new ArrayList<>();
        for (MigrationLedger.Entry entry : ledger.snapshot()) {
            if (entry.state() == MigrationLedger.State.PENDING
                    || entry.state() == MigrationLedger.State.FAILED) {
                pending.add(entry.name());
            }
        }
        return List.copyOf(pending);
    }

    @Override
    public List<String> apply() {
        OrderedApplier.ApplyReport report = applier.applyAll();
        if (report.stoppedAt() != null) {
            recovery.recordFailure(report.stoppedAt());
        } else {
            for (String name : report.applied()) {
                recovery.recordSuccess(name);
            }
        }
        return report.applied();
    }

    @Override
    public void failNext(String name) {
        applier.failNext(name);
    }

    @Override
    public void retry(String name) {
        recovery.retry(name);
    }

    @Override
    public void resolve(String name) {
        recovery.resolve(name);
    }

    @Override
    public int failures(String name) {
        return recovery.failures(name);
    }

    @Override
    public void baseline(String upToName) {
        baselines.mark(ledger, upToName);
    }

    @Override
    public String squash(String fromName, String toName) {
        return new Squash(ledger, checksums).merge(fromName, toName);
    }

    @Override
    public String verify(String actualSchemaFingerprint) {
        for (MigrationLedger.Entry entry : ledger.snapshot()) {
            if (entry.state() == MigrationLedger.State.APPLIED && checksums.bound(entry.name())) {
                checksums.verify(entry.name(), entry.content());
            }
        }
        DriftDetector.DriftReport report = new DriftDetector(ledger, checksums).verify(actualSchemaFingerprint);
        return report.consistent() ? "CONSISTENT" : "DRIFT";
    }

    @Override
    public List<String> applied() {
        return applier.appliedNames();
    }

    @Override
    public List<String> revisionsShape() {
        return List.of("revision", "type", "key", "value");
    }
}
