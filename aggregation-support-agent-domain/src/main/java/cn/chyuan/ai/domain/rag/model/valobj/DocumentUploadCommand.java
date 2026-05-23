package cn.chyuan.ai.domain.rag.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档上传命令值对象 — 封装用户上传文档的请求参数
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DocumentUploadCommand {

    /** 文件名 */
    private String fileName;

    /** 文件内容（UTF-8 文本） */
    private String content;

    /** 原始二进制内容（用于 PDF/Word/HTML 等二进制格式） */
    private byte[] rawContent;

    /** MIME 类型（text/plain 或 text/markdown） */
    private String mimeType;

    /** 上传用户ID */
    private String userId;

    /** 租户ID */
    private String tenantId;

}
