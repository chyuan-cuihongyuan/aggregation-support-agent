package cn.chyuan.ai.domain.rag.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 向量检索结果值对象 — 封装一次相似性搜索的单条结果
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class VectorSearchResultVO {

    /** 匹配到的文档内容 */
    private String content;

    /** 相似度分数（L2 距离，越小越相似） */
    private float score;

    /** 文档元数据（来源文件、分块索引等） */
    private Map<String, Object> metadata;

}
