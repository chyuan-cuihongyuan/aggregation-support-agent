package cn.chyuan.ai.domain.migratekernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * drift 漂移检测（工单 1156 EZ6，prisma 思想）。
 * 期望指纹 = 活跃已应用条内容依序串接 sha256；与实际 schema 指纹比对；
 * 不一致即漂移告警；账本中未应用条（实际缺条）列出；一致通过。
 */
public final class DriftDetector {

    /** 漂移报告：一致/期望指纹/实际指纹/实际缺失（未应用）条 */
    public record DriftReport(boolean consistent, String expectedFingerprint,
                              String actualFingerprint, List<String> missingApplied) {
    }

    private final MigrationLedger ledger;
    private final Checksums checksums;

    public DriftDetector(MigrationLedger ledger, Checksums checksums) {
        this.ledger = ledger;
        this.checksums = checksums;
    }

    /** 校验：actualFingerprint 为实际 schema 指纹 */
    public DriftReport verify(String actualFingerprint) {
        StringBuilder expected = new StringBuilder();
        List<String> missingApplied = new ArrayList<>();
        for (MigrationLedger.Entry entry : ledger.activeEntries()) {
            if (entry.state() == MigrationLedger.State.APPLIED
                    || entry.state() == MigrationLedger.State.BASELINED) {
                expected.append(checksums.of(entry.name()) == null ? Checksums.fingerprint(entry.content()) : checksums.of(entry.name()));
                expected.append('\n');
            } else {
                missingApplied.add(entry.name());
            }
        }
        String expectedFp = Checksums.fingerprint(expected.toString());
        boolean consistent = expectedFp.equals(actualFingerprint) && missingApplied.isEmpty();
        return new DriftReport(consistent, expectedFp, actualFingerprint, List.copyOf(missingApplied));
    }
}
