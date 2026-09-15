package cn.chyuan.ai.domain.tmemory.adapter.port;

import java.util.List;

/**
 * 情节摘要生成端口（工单 0366 AS5）：LLM 端口，失败时调用方模板兜底。
 */
public interface ISummaryPort {

    /**
     * 对一批事实句子生成情节摘要。
     *
     * @param facts 事实句子清单
     * @return 摘要文本
     */
    String summarize(List<String> facts);
}
