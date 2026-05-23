package cn.chyuan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * RAG 检索追踪记录 PO — 对应 rag_trace 表
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RagTracePO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 追踪ID（请求维度唯一） */
    private String traceId;

    /** 租户ID */
    private String tenantId;

    /** 归属用户ID */
    private String ownerUserId;

    /** 会话ID（AIOps 工具触发时可为空） */
    private String sessionId;

    /** 智能体ID */
    private String agentId;

    /** 原始查询文本 */
    private String queryText;

    /** Query 改写后的检索文本 */
    private String rewriteText;

    /** 检索 TopK */
    private Integer retrievalTopk;

    /** 命中证据 JSON 字符串（落库到 source_docs 列） */
    private String sourceDocs;

    /** 答案质量分（预留） */
    private Double answerScore;

    /** 幻觉率（预留） */
    private Double hallucinationScore;

    /** 创建时间 */
    private Date createTime;
}
