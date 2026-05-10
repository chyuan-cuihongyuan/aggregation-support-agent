package cn.chyuan.ai.domain.rag.adapter.port;

import cn.chyuan.ai.domain.rag.model.valobj.ParsedDocumentVO;

/**
 * 文档解析器接口 — 将不同格式的文档解析为统一的文本结构
 * <p>
 * 支持的文档格式：
 * <ul>
 *   <li>TXT - 纯文本文件</li>
 *   <li>Markdown - Markdown格式文档</li>
 *   <li>PDF - PDF文档</li>
 *   <li>Word - Word文档（.doc, .docx）</li>
 *   <li>HTML - HTML网页</li>
 * </ul>
 */
public interface IDocumentParser {

    /**
     * 解析文档 — 将文档内容解析为结构化文本
     *
     * @param content    文档内容（字节数组）
     * @param fileName   文件名
     * @param mimeType   MIME类型
     * @return 解析后的文档对象
     */
    ParsedDocumentVO parse(byte[] content, String fileName, String mimeType);

    /**
     * 判断是否支持该文档类型
     *
     * @param mimeType   MIME类型
     * @param fileName   文件名
     * @return true表示支持该类型
     */
    boolean supports(String mimeType, String fileName);

}
