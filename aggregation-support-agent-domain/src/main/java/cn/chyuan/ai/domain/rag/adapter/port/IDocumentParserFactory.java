package cn.chyuan.ai.domain.rag.adapter.port;

import cn.chyuan.ai.domain.rag.model.valobj.ParsedDocumentVO;

/**
 * 文档解析器工厂接口 — 根据文档类型选择合适的解析器
 */
public interface IDocumentParserFactory {

    /**
     * 解析文档 — 自动选择合适的解析器
     *
     * @param content  文档内容（字节数组）
     * @param fileName 文件名
     * @param mimeType MIME类型
     * @return 解析后的文档对象
     */
    ParsedDocumentVO parse(byte[] content, String fileName, String mimeType);

    /**
     * 判断是否支持该文档类型
     */
    boolean isSupported(String mimeType, String fileName);

}
