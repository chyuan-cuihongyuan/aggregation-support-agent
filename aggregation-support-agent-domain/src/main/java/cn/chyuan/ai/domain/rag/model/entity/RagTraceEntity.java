package cn.chyuan.ai.domain.rag.model.entity;

import cn.chyuan.ai.domain.rag.model.valobj.RagSourceVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * RAG 检索追踪记录 — 每次检索写一条，用于证据回溯与质量评估
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RagTraceEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 追踪ID（请求维度唯一） */
    private String traceId;

    private String tenantId;

    private String ownerUserId;

    /** 会话ID（AIOps 工具触发时可为空） */
    private String sessionId;

    /** 智能体ID（AIOps 工具触发时可为空） */
    private String agentId;

    /** 原始查询文本 */
    private String queryText;

    /** Query 改写后的文本 */
    private String rewriteText;

    /** 检索 TopK */
    private Integer retrievalTopk;

    /** 命中证据列表（落库为 JSON） */
    private List<RagSourceVO> sources;

    /** 父块ID列表（工单 0166 父子分块：JSON 数组文本，去重后；存量数据为 null） */
    private String parentIds;

    /** 父块文本列表（工单 0166：JSON 数组文本，去重后；存量数据为 null） */
    private String parentTexts;

    /** 答案质量分（预留，本期可为 null） */
    private Double answerScore;

    /** 幻觉率（预留，本期可为 null） */
    private Double hallucinationScore;

    private Date createTime;
}
