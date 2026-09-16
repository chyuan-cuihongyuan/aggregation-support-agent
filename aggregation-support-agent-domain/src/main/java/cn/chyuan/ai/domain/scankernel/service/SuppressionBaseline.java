package cn.chyuan.ai.domain.scankernel.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 抑制标注与基线对比（工单 0455 BC5，沿 0020 误报登记先例）。
 * 发现指纹（规则×文件×行内容 SHA-256）+ {@code // nosemgrep} 行内抑制
 * （当前行与下一行命中 SUPPRESSED）+ 基线集合对比（基线内标记 BASELINE）+ 新增清单。纯函数。
 */
public class SuppressionBaseline {

    /** 状态 */
    public enum Status {
        OPEN, SUPPRESSED, BASELINE
    }

    /** 发现 */
    public record Finding(String fingerprint, String ruleId, String file, int line, int column,
                          String severity, Status status, String snippet) {
    }

    /** 指纹：规则×文件×行内容 SHA-256 */
    public static String fingerprint(String ruleId, String file, int line, String lineContent) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String payload = ruleId + ":" + file + ":" + line + ":" + lineContent.strip();
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 行内抑制：命中行自身带 nosemgrep，或上一行为独立 nosemgrep 注释行 → SUPPRESSED */
    public Status suppress(List<String> lines, int line) {
        if (hasInlineTag(lines, line) || standaloneTagAbove(lines, line)) {
            return Status.SUPPRESSED;
        }
        return Status.OPEN;
    }

    private static boolean hasInlineTag(List<String> lines, int lineNumber) {
        int index = lineNumber - 1;
        return index >= 0 && index < lines.size() && lines.get(index).contains("nosemgrep");
    }

    /** 独立注释行（以 // 开头且含 nosemgrep）抑制其下一行 */
    private static boolean standaloneTagAbove(List<String> lines, int lineNumber) {
        int index = lineNumber - 2;
        if (index < 0 || index >= lines.size()) {
            return false;
        }
        String stripped = lines.get(index).strip();
        return stripped.startsWith("//") && stripped.contains("nosemgrep");
    }

    /** 基线对比：基线内 BASELINE，否则保留原状态 */
    public Status baseline(Status current, String fingerprint, Set<String> baselineFingerprints) {
        if (current == Status.OPEN && baselineFingerprints.contains(fingerprint)) {
            return Status.BASELINE;
        }
        return current;
    }

    /** 新增清单：仅 OPEN */
    public List<Finding> novel(List<Finding> findings) {
        List<Finding> out = new java.util.ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Finding finding : findings) {
            if (finding.status() == Status.OPEN && seen.add(finding.fingerprint())) {
                out.add(finding);
            }
        }
        return out;
    }
}
