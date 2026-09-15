package cn.chyuan.ai.domain.tmemory.service;

import cn.chyuan.ai.domain.tmemory.model.valobj.MemoryEdgeVO;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 双时间线记忆边工厂（工单 0362 AS1，graphiti bi-temporal 思想）。
 * 事实时间由调用方给定时钟提供（validFrom/validTo），事务时间为工厂内单调递增序号。
 * domain 纯逻辑，无框架依赖。
 */
public class BiTemporalEdgeFactory {

    private final AtomicLong sequence = new AtomicLong(0);

    /**
     * 构建一条事实边（校验 + 事务序号分配）。
     */
    public MemoryEdgeVO create(String subject, String predicate, String object,
                               long validFrom, Long validTo, double confidence,
                               String source, String kind) {
        if (isBlank(subject) || isBlank(predicate) || isBlank(object)) {
            throw new IllegalArgumentException("三元组主谓宾均不可为空");
        }
        if (validTo != null && validTo < validFrom) {
            throw new IllegalArgumentException("失效时间不可早于生效时间");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("置信度须在 0-1 区间: " + confidence);
        }
        if (!"EPISODIC".equals(kind) && !"SEMANTIC".equals(kind)) {
            throw new IllegalArgumentException("边类别须为 EPISODIC/SEMANTIC: " + kind);
        }
        long seq = sequence.incrementAndGet();
        return MemoryEdgeVO.builder()
                .edgeId("me-" + seq)
                .subject(subject.trim())
                .predicate(predicate.trim())
                .object(object.trim())
                .validFrom(validFrom)
                .validTo(validTo)
                .ingestSeq(seq)
                .confidence(confidence)
                .source(source == null ? "unknown" : source)
                .kind(kind)
                .accessCount(0)
                .score(0.5)
                .build();
    }

    /** 显式失效（工单 0364 AS3 SUPERSEDED 语义）：打失效戳与原因，返回新副本 */
    public MemoryEdgeVO invalidate(MemoryEdgeVO edge, long validTo, String reason) {
        if (!edge.active()) {
            throw new IllegalArgumentException("边已失效，不可重复失效: " + edge.getEdgeId());
        }
        if (validTo < edge.getValidFrom()) {
            throw new IllegalArgumentException("失效时间不可早于生效时间");
        }
        return edge.toBuilder().validTo(validTo).invalidReason(reason).build();
    }

    /** 已分配序号数（测试观测） */
    public long allocated() {
        return sequence.get();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
