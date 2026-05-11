package cn.chyuan.ai.domain.rag.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 文档分块实体 — 表示文档被切分后的一个片段，包含原文内容、向量表示和元数据
 * <p>
 * 用于 RAG 流程：文档上传 → 分块 → 嵌入 → 存储到向量数据库
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DocumentChunkEntity {

    /** 分块唯一标识 */
    private String id;

    /** 分块文本内容 */
    private String content;

    /** 文本对应的嵌入向量（DashScope text-embedding-v4 为 1024 维） */
    private float[] vector;

    /**
     * 元数据 — 记录分块的来源信息
     * <ul>
     *   <li>_source: 源文件路径</li>
     *   <li>_file_name: 文件名</li>
     *   <li>_extension: 文件扩展名</li>
     *   <li>chunkIndex: 当前分块索引</li>
     *   <li>totalChunks: 总分块数</li>
     *   <li>title: 分块标题（如果检测到）</li>
     * </ul>
     */
    private Map<String, Object> metadata;

}
