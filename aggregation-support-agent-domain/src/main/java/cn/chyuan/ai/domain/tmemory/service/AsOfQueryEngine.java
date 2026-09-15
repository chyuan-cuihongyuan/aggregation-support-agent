package cn.chyuan.ai.domain.tmemory.service;

import cn.chyuan.ai.domain.tmemory.model.valobj.EdgeVisibilityVO;
import cn.chyuan.ai.domain.tmemory.model.valobj.MemoryEdgeVO;

import java.util.ArrayList;
import java.util.List;

/**
 * 时间切片 as-of 查询（工单 0365 AS4）。
 * VALID 口径：as-of 时刻的事实有效视图（validFrom<=t 且未失效或失效在 t 之后）；
 * INGESTED 口径：事务时间线视图（该序号前已入库的全部边，含已失效）。逐边可解释。
 */
public class AsOfQueryEngine {

    /** 口径常量 */
    public static final String SCOPE_VALID = "VALID";
    public static final String SCOPE_INGESTED = "INGESTED";

    /** 事实时间 as-of：返回当时有效视图（失效边界开区间：validTo>t 可见） */
    public List<MemoryEdgeVO> asOfValid(List<MemoryEdgeVO> edges, long asOfMs) {
        List<MemoryEdgeVO> out = new ArrayList<>();
        for (MemoryEdgeVO edge : edges == null ? List.<MemoryEdgeVO>of() : edges) {
            if (edge.getValidFrom() <= asOfMs && (edge.getValidTo() == null || edge.getValidTo() > asOfMs)) {
                out.add(edge);
            }
        }
        return out;
    }

    /** 事务时间 as-of：该序号前已入库的全部边（含已失效） */
    public List<MemoryEdgeVO> asOfIngested(List<MemoryEdgeVO> edges, long ingestSeq) {
        List<MemoryEdgeVO> out = new ArrayList<>();
        for (MemoryEdgeVO edge : edges == null ? List.<MemoryEdgeVO>of() : edges) {
            if (edge.getIngestSeq() <= ingestSeq) {
                out.add(edge);
            }
        }
        return out;
    }

    /** 逐边可见性解释（VALID 口径） */
    public List<EdgeVisibilityVO> explainValid(List<MemoryEdgeVO> edges, long asOfMs) {
        List<EdgeVisibilityVO> out = new ArrayList<>();
        for (MemoryEdgeVO edge : edges == null ? List.<MemoryEdgeVO>of() : edges) {
            if (edge.getValidFrom() > asOfMs) {
                out.add(EdgeVisibilityVO.of(edge, false, SCOPE_VALID, EdgeVisibilityVO.REASON_NOT_YET));
            } else if (edge.getValidTo() != null && edge.getValidTo() <= asOfMs) {
                out.add(EdgeVisibilityVO.of(edge, false, SCOPE_VALID, EdgeVisibilityVO.REASON_INVALIDATED));
            } else {
                out.add(EdgeVisibilityVO.of(edge, true, SCOPE_VALID, EdgeVisibilityVO.REASON_ACTIVE));
            }
        }
        return out;
    }

    /** 两口径差异：事务视图含已失效边，事实视图只含当时有效（便于审计对照） */
    public List<MemoryEdgeVO> invalidatedBetween(List<MemoryEdgeVO> edges, long ingestSeq, long asOfMs) {
        List<MemoryEdgeVO> ingested = asOfIngested(edges, ingestSeq);
        List<MemoryEdgeVO> out = new ArrayList<>();
        for (MemoryEdgeVO edge : ingested) {
            if (edge.getValidTo() != null && edge.getValidTo() <= asOfMs) {
                out.add(edge);
            }
        }
        return out;
    }
}
