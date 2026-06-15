package cn.chyuan.ai.domain.rag.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 带证据链的一次 RAG 检索输出
 * <p>
 * 与裸 List&lt;VectorSearchResultVO&gt; 相比，本对象额外携带：
 * <ul>
 *   <li>traceId — 用于关联 rag_trace 表与 ChatResponse</li>
 *   <li>rewriteQuery — Query 改写后的真正检索文本</li>
 *   <li>sources — 提取后的证据片段（前端可直接展示）</li>
 *   <li>rawResults — 原始检索结果（保留扁平结构供工具回包）</li>
 * </ul>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SearchOutcomeVO {

    private String traceId;
    private String originalQuery;
    private String rewriteQuery;
    private Integer topK;
    private List<RagSourceVO> sources;
    private List<VectorSearchResultVO> rawResults;

    /** 检索质量门控是否通过（true=通过，false=拒绝回答） */
    private Boolean qualityGatePassed;

    /** 质量门控拒绝原因（当 qualityGatePassed=false 时设置） */
    private String rejectionMessage;

    /** 检索结果平均相关性分数 */
    private Double averageScore;

    /** 是否触发了 Corrective RAG 纠正 */
    private Boolean cragTriggered;

    /** CRAG 纠正次数 */
    private Integer cragRetryCount;

    /** CRAG 纠正后的最终平均分数 */
    private Double cragFinalScore;
}
