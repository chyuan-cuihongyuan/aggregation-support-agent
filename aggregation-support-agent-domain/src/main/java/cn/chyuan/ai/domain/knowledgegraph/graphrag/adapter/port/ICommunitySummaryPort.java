package cn.chyuan.ai.domain.knowledgegraph.graphrag.adapter.port;

import java.util.List;

/**
 * 社区摘要生成端口（AM3：LLM 适配 + domain 模板兜底）。
 * graphrag 社区摘要思想的六边形端口。
 */
public interface ICommunitySummaryPort {

    /**
     * 生成单个社区摘要。
     *
     * @param communityId   社区ID
     * @param memberKeys    成员节点键（有序）
     * @param relationCount 社区内部关系边数
     * @return 摘要文本；返回 null 或抛异常时由调用方走模板兜底
     */
    String summarize(String communityId, List<String> memberKeys, int relationCount);
}
