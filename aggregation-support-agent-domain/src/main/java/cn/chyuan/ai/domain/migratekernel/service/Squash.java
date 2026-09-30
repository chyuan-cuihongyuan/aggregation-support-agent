package cn.chyuan.ai.domain.migratekernel.service;

import java.util.List;

/**
 * squash 合并（工单 1155 EZ5，prisma 思想）。
 * 仅连续已应用区间可合并为一条（名字 from..to，内容串接，checksum 合并绑定）；
 * 前身转 MERGED 退出活跃账本，新环境以合并条起账；非连续/越界/未知名拒绝。
 */
public final class Squash {

    private final MigrationLedger ledger;
    private final Checksums checksums;

    public Squash(MigrationLedger ledger, Checksums checksums) {
        this.ledger = ledger;
        this.checksums = checksums;
    }

    /** 合并 [fromName, toName] 闭区间，返回合并条名 */
    public String merge(String fromName, String toName) {
        MigrationLedger.Entry from = ledger.get(fromName);
        MigrationLedger.Entry to = ledger.get(toName);
        List<MigrationLedger.Entry> snapshot = ledger.snapshot();
        int i = snapshot.indexOf(from);
        int j = snapshot.indexOf(to);
        if (i < 0 || j < 0 || i > j) {
            throw new IllegalArgumentException("非法合并区间: " + fromName + ".." + toName);
        }
        StringBuilder content = new StringBuilder();
        for (int k = i; k <= j; k++) {
            MigrationLedger.Entry entry = snapshot.get(k);
            if (entry.state() != MigrationLedger.State.APPLIED && entry.state() != MigrationLedger.State.BASELINED) {
                throw new IllegalStateException("非连续已应用区间拒绝: " + entry.name());
            }
            content.append(entry.content()).append('\n');
        }
        String mergedName = fromName + ".." + toName;
        if (ledger.has(mergedName)) {
            throw new IllegalStateException("重复合并条: " + mergedName);
        }
        MigrationLedger.Entry merged = ledger.record(mergedName, content.toString());
        merged.transition(MigrationLedger.State.APPLIED);
        checksums.bind(mergedName, content.toString());
        for (int k = i; k <= j; k++) {
            snapshot.get(k).transition(MigrationLedger.State.MERGED);
        }
        ledger.replaceRange(fromName, toName, mergedName);
        return mergedName;
    }
}
