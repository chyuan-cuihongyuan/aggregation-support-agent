package cn.chyuan.ai.domain.docintel.adapter.port;

import java.util.List;

/**
 * 文档解析端口（工单 0395 AV9）：文档引用→元素框结构；真实 PDF/图像解析引擎挂雾，假解析器承接。
 */
public interface IDocParsePort {

    /** 解析结果 */
    record ParseResult(String docRef, int page, List<PageElement> elements, long parseMs) {
    }

    /** 页面元素（与 docintel 版面块同构） */
    record PageElement(String id, double x, double y, double width, double height, String type, String text) {
    }

    /**
     * 解析文档。
     *
     * @param docRef 文档引用（对象存储键/URL）
     * @param page   页码（从 1 起）
     */
    ParseResult parse(String docRef, int page);
}
