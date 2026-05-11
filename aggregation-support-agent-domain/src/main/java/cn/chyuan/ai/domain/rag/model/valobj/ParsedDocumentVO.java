package cn.chyuan.ai.domain.rag.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 解析后的文档值对象 — 封装文档解析结果
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ParsedDocumentVO {

    /** 文件名 */
    private String fileName;

    /** 文件扩展名 */
    private String extension;

    /** MIME类型 */
    private String mimeType;

    /** 解析后的纯文本内容 */
    private String textContent;

    /** 文档结构（章节列表） */
    private List<DocumentSection> sections;

    /** 文档元数据 */
    private Map<String, Object> metadata;

    /**
     * 文档章节内部类
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DocumentSection {
        /** 章节标题 */
        private String title;
        /** 章节内容 */
        private String content;
        /** 章节层级（1-6） */
        private int level;
        /** 章节在原文中的位置 */
        private int position;
    }

}
