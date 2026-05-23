package cn.chyuan.ai.domain.rag.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * RAG 命中证据片段 — 用于展示给用户的来源说明，落库到 rag_trace.source_docs
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RagSourceVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 文档ID（document_metadata.document_id） */
    private String documentId;

    /** 文档原始文件名 */
    private String documentName;

    /** 分块ID（chunkId / Milvus 主键） */
    private String chunkId;

    /** 分块在文档中的索引 */
    private Integer chunkIndex;

    /** 相似度分数 */
    private Float score;

    /** 检索类型: vector / bm25 / hybrid / rerank */
    private String retrievalType;

    /** 内容片段（截断 200 字符以内，便于前端展示） */
    private String snippet;
}
