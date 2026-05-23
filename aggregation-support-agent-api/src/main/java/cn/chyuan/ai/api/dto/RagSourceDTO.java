package cn.chyuan.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * RAG 命中证据片段 DTO — 用于在 API 层向前端返回检索证据
 * <p>
 * 与领域层 {@code RagSourceVO} 字段一一对应，由 ChatService 在出口完成 VO → DTO 转换，
 * 避免 api 模块直接依赖 domain 模块。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RagSourceDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 文档 ID（document_metadata.document_id） */
    private String documentId;

    /** 文档原始文件名 */
    private String documentName;

    /** 分块 ID（chunkId / Milvus 主键） */
    private String chunkId;

    /** 分块在文档中的索引 */
    private Integer chunkIndex;

    /** 相似度分数 */
    private Float score;

    /** 检索类型：vector / bm25 / hybrid / rerank */
    private String retrievalType;

    /** 内容片段（截断 200 字符以内，便于前端展示） */
    private String snippet;
}
