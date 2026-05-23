package cn.chyuan.ai.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 对话响应 DTO — 同步对话接口返回体
 */
@Data
public class ChatResponseDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 模型回答的正文内容 */
    private String content;

    /** 本次检索的追踪 ID（关联 rag_trace 表），无 RAG 调用时为空字符串 */
    private String traceId;

    /** RAG 检索命中的证据片段列表（可能为空，例如非 RAG 智能体） */
    private List<RagSourceDTO> sources;

}
