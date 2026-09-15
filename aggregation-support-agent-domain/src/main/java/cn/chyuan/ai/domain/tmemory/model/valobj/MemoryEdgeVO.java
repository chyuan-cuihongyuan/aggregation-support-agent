package cn.chyuan.ai.domain.tmemory.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 时序记忆事实边值对象（工单 0362 AS1，graphiti bi-temporal 思想）。
 * 双时间线：validFrom/validTo 为事实时间（事实何时成立/失效），
 * ingestSeq 为事务时间（入库单调序号）；validTo 为空表示当前仍有效。
 */
@Data
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class MemoryEdgeVO {

    /** 边标识（me-序号） */
    private String edgeId;

    /** 主语实体名 */
    private String subject;

    /** 谓语（关系） */
    private String predicate;

    /** 宾语实体名 */
    private String object;

    /** 事实生效时间（epoch ms） */
    private long validFrom;

    /** 事实失效时间（null 表示仍有效，工单 0364 AS3 失效戳） */
    private Long validTo;

    /** 失效原因（SUPERSEDED/CONFLICT，未失效为 null） */
    private String invalidReason;

    /** 事务时间：入库单调序号（从 1 递增） */
    private long ingestSeq;

    /** 置信度（0-1） */
    private double confidence;

    /** 来源标识（official/media/ugc/unknown 等） */
    private String source;

    /** 边类别：EPISODIC 情景 / SEMANTIC 语义（工单 0366 AS5 晋升产物） */
    private String kind;

    /** 访问计数（工单 0369 AS8 裁剪因子） */
    private int accessCount;

    /** 价值分数（0-1） */
    private double score;

    /** 晋升来源批次（SEMANTIC 边填写，AS5） */
    private java.util.List<String> promotedFrom;

    /** 当前是否有效 */
    public boolean active() {
        return validTo == null;
    }
}
