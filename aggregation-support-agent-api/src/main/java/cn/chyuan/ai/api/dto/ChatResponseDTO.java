package cn.chyuan.ai.api.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 对话响应 DTO — 同步对话接口返回体
 *
 * 注意：traceId 和 sources 仅在内部收集用于可观测性上报，不再返回给前端展示
 */
@Data
public class ChatResponseDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 模型回答的正文内容 */
    private String content;

}
