package cn.chyuan.ai.domain.tmemory.service;

import cn.chyuan.ai.domain.tmemory.model.valobj.MemoryEdgeVO;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 记忆图压缩与快照（工单 0369 AS8，letta 记忆分页/压缩思想）。
 * 失效边归档（保留审计）；低价值边裁剪（访问频率×分数低于阈值且非语义边——语义边保护）；
 * 快照经 MiniSnapshotCodec 导出，重放重建等价活跃集。
 */
public class MemoryCompactor {

    private final double pruneThreshold;

    public MemoryCompactor(double pruneThreshold) {
        if (pruneThreshold < 0) {
            throw new IllegalArgumentException("裁剪阈值不可为负");
        }
        this.pruneThreshold = pruneThreshold;
    }

    /** 压缩报告 */
    public record Report(List<MemoryEdgeVO> active, List<MemoryEdgeVO> archived,
                         List<MemoryEdgeVO> pruned, int archivedCount, int prunedCount, int activeCount) {
    }

    public Report compact(List<MemoryEdgeVO> edges) {
        List<MemoryEdgeVO> active = new ArrayList<>();
        List<MemoryEdgeVO> archived = new ArrayList<>();
        List<MemoryEdgeVO> pruned = new ArrayList<>();
        for (MemoryEdgeVO edge : edges == null ? List.<MemoryEdgeVO>of() : edges) {
            if (!edge.active()) {
                archived.add(edge);
            } else if (!"SEMANTIC".equals(edge.getKind())
                    && (long) edge.getAccessCount() * edge.getScore() < pruneThreshold) {
                pruned.add(edge);
            } else {
                active.add(edge);
            }
        }
        active.sort(Comparator.comparingLong(MemoryEdgeVO::getIngestSeq));
        archived.sort(Comparator.comparingLong(MemoryEdgeVO::getIngestSeq));
        pruned.sort(Comparator.comparingLong(MemoryEdgeVO::getIngestSeq));
        return new Report(active, archived, pruned, archived.size(), pruned.size(), active.size());
    }

    /** 快照导出（确定性 JSON：边按 ingestSeq 排序） */
    public String snapshot(Report report, String name, long createdAtMs) {
        return MiniSnapshotCodec.encode(new MiniSnapshotCodec.Snapshot(name, createdAtMs,
                toSnap(report.active()), toSnap(report.archived())));
    }

    /** 快照重放重建：解析回活跃/归档清单（与导出前等价） */
    public MiniSnapshotCodec.Snapshot restore(String snapshotJson) {
        return MiniSnapshotCodec.decode(snapshotJson);
    }

    private List<MiniSnapshotCodec.SnapEdge> toSnap(List<MemoryEdgeVO> edges) {
        List<MiniSnapshotCodec.SnapEdge> out = new ArrayList<>();
        for (MemoryEdgeVO edge : edges) {
            out.add(new MiniSnapshotCodec.SnapEdge(edge.getEdgeId(), edge.getSubject(), edge.getPredicate(),
                    edge.getObject(), edge.getValidFrom(), edge.getValidTo(), edge.getInvalidReason(),
                    edge.getIngestSeq(), edge.getConfidence(), edge.getSource(), edge.getKind(),
                    edge.getAccessCount(), edge.getScore()));
        }
        return out;
    }
}
