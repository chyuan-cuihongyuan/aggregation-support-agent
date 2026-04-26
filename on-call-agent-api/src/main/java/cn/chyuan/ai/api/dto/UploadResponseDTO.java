package cn.chyuan.ai.api.dto;

import lombok.Data;

/**
 * 文档上传响应 DTO — 返回上传文档的处理结果
 */
@Data
public class UploadResponseDTO {

    /** 文档标识（由文件名和时间戳生成） */
    private String documentId;

    /** 文档被切分成的块数 */
    private Integer chunkCount;

    /** 处理状态：success / failed */
    private String status;

    /** 状态描述信息 */
    private String message;

}
