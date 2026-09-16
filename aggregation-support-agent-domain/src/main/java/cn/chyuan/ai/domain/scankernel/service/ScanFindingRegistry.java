package cn.chyuan.ai.domain.scankernel.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 发现清单注册表（工单 0457 BC7）。
 * 指纹唯一 + 批次幂等（同批次同指纹不重复入清单）+ 状态机（OPEN/SUPPRESSED/BASELINE）。
 * scan-kernel.enabled 默认关不接 Mimosa 链路（0417-D6 只读借鉴）。持久化面=第 29 表 scan_finding。
 */
public class ScanFindingRegistry {

    /** 注册条目 */
    public record Registered(String fingerprint, String ruleId, String file, int line,
                             String severity, SuppressionBaseline.Status status, String batchId) {
    }

    private final Set<String> seenFingerprints = new HashSet<>();
    private final List<Registered> findings = new ArrayList<>();

    /** 入清单：同指纹（无论批次）幂等跳过；返回是否新入 */
    public synchronized boolean add(SuppressionBaseline.Finding finding, String batchId) {
        if (!seenFingerprints.add(finding.fingerprint())) {
            return false;
        }
        findings.add(new Registered(finding.fingerprint(), finding.ruleId(), finding.file(),
                finding.line(), finding.severity(), finding.status(), batchId));
        return true;
    }

    /** 批次入清单：返回新入条数（同批次重放幂等=0） */
    public synchronized int addBatch(List<SuppressionBaseline.Finding> batch, String batchId) {
        int added = 0;
        for (SuppressionBaseline.Finding finding : batch) {
            if (add(finding, batchId)) {
                added++;
            }
        }
        return added;
    }

    public synchronized List<Registered> list() {
        return List.copyOf(findings);
    }

    public synchronized int size() {
        return findings.size();
    }
}
