package cn.chyuan.ai.domain.migratekernel.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 顺序应用器（工单 1152 EZ2，prisma 思想）。
 * 按登记序逐个 apply；已应用/基线/跳过条幂等跳过；中途失败停后续并留状态；
 * failNext 注入失败（测试口径），失败条经恢复器重置后续跑。
 */
public final class OrderedApplier {

    private final MigrationLedger ledger;
    private final Checksums checksums;
    private final Set<String> failNext = new LinkedHashSet<>();

    public OrderedApplier(MigrationLedger ledger, Checksums checksums) {
        this.ledger = ledger;
        this.checksums = checksums;
    }

    /** 应用结果：本轮实际应用名 + 停止点（null 表示全部应用完） */
    public record ApplyReport(List<String> applied, String stoppedAt) {
    }

    /** 注入下一轮应用时该迁移失败（测试口径）；未知迁移拒绝 */
    public void failNext(String name) {
        ledger.get(name);
        failNext.add(name);
    }

    /** 顺序应用：PENDING 才执行；APPLIED/BASELINED/SKIPPED/MERGED 幂等跳过；FAILED 即停 */
    public ApplyReport applyAll() {
        List<String> applied = new ArrayList<>();
        for (MigrationLedger.Entry entry : ledger.snapshot()) {
            switch (entry.state()) {
                case PENDING -> {
                    if (failNext.remove(entry.name())) {
                        entry.transition(MigrationLedger.State.FAILED);
                        return new ApplyReport(List.copyOf(applied), entry.name());
                    }
                    entry.transition(MigrationLedger.State.APPLIED);
                    checksums.bind(entry.name(), entry.content());
                    applied.add(entry.name());
                }
                case FAILED -> {
                    return new ApplyReport(List.copyOf(applied), entry.name());
                }
                default -> {
                    // APPLIED/BASELINED/SKIPPED/MERGED 幂等跳过
                }
            }
        }
        return new ApplyReport(List.copyOf(applied), null);
    }

    /** 已应用条目名序（含基线，不含合并前身与跳过） */
    public List<String> appliedNames() {
        return ledger.snapshot().stream()
                .filter(e -> e.state() == MigrationLedger.State.APPLIED
                        || e.state() == MigrationLedger.State.BASELINED)
                .map(MigrationLedger.Entry::name)
                .toList();
    }
}
