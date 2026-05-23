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
}
