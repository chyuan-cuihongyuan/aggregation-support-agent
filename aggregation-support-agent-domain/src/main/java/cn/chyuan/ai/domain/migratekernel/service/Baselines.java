package cn.chyuan.ai.domain.migratekernel.service;

import java.util.List;

/**
 * baseline 基线（工单 1154 EZ4，prisma 思想）。
 * 基线将既有未应用段（至多含指定条）标记 BASELINED 视作已存在；
 * 基线只对起始段生效——区间内已应用条即与 checksum 账目矛盾拒绝；
 * 重复基线拒绝；基线后新迁移正常续应用。
 */
public final class Baselines {

    private boolean marked;

    public boolean marked() {
        return marked;
    }

    /** 基线：将 ledger 中至多含 upToName 的前置 PENDING 段转 BASELINED */
    public void mark(MigrationLedger ledger, String upToName) {
        if (marked) {
            throw new IllegalStateException("重复基线拒绝");
        }
        List<MigrationLedger.Entry> snapshot = ledger.snapshot();
        int boundary = -1;
        if (upToName != null) {
            for (int i = 0; i < snapshot.size(); i++) {
                if (snapshot.get(i).name().equals(upToName)) {
                    boundary = i;
                    break;
                }
            }
            if (boundary < 0) {
                throw new IllegalArgumentException("未知迁移: " + upToName);
            }
        }
        for (int i = 0; i <= boundary; i++) {
            MigrationLedger.Entry entry = snapshot.get(i);
            if (entry.state() == MigrationLedger.State.APPLIED) {
                throw new IllegalStateException("基线与 checksum 账目矛盾（区间含已应用条）: " + entry.name());
            }
            if (entry.state() == MigrationLedger.State.PENDING) {
                entry.transition(MigrationLedger.State.BASELINED);
            }
        }
        marked = true;
    }
}
