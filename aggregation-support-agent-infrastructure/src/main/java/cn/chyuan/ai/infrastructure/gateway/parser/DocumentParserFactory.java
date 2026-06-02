package cn.chyuan.ai.infrastructure.gateway.parser;

import cn.chyuan.ai.domain.rag.adapter.port.IDocumentParser;
import cn.chyuan.ai.domain.rag.adapter.port.IDocumentParserFactory;
import cn.chyuan.ai.domain.rag.model.valobj.ParsedDocumentVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文档解析器工厂 — 根据文档类型选择合适的解析器
 */
@Slf4j
@Component
public class DocumentParserFactory implements IDocumentParserFactory {

    private final List<IDocumentParser> parsers;

    public DocumentParserFactory(List<IDocumentParser> parsers) {
        this.parsers = parsers;
        log.info("文档解析器工厂初始化，已注册 {} 个解析器", parsers.size());
    }

    /**
     * 解析文档 — 自动选择合适的解析器
     *
     * @param content  文档内容（字节数组）
     * @param fileName 文件名
     * @param mimeType MIME类型
     * @return 解析后的文档对象
     */
    @Override
    public ParsedDocumentVO parse(byte[] content, String fileName, String mimeType) {
        IDocumentParser parser = getParser(mimeType, fileName);

        if (parser == null) {
            log.warn("未找到合适的文档解析器，使用默认TXT解析器: fileName={}, mimeType={}", fileName, mimeType);
            parser = getDefaultParser();
        }

        log.info("使用 {} 解析文档: {}", parser.getClass().getSimpleName(), fileName);
        return parser.parse(content, fileName, mimeType);
    }

    /**
     * 获取支持指定文档类型的解析器
     */
    public IDocumentParser getParser(String mimeType, String fileName) {
        for (IDocumentParser parser : parsers) {
            if (parser.supports(mimeType, fileName)) {
                return parser;
            }
        }
        return null;
    }

    /**
     * 获取默认解析器（TXT）
     */
    private IDocumentParser getDefaultParser() {
        return parsers.stream()
                .filter(p -> p instanceof TxtDocumentParser)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("未找到默认的TXT文档解析器"));
    }

    /**
     * 判断是否支持该文档类型
     */
    @Override
    public boolean isSupported(String mimeType, String fileName) {
        return parsers.stream().anyMatch(p -> p.supports(mimeType, fileName));
    }

    /**
     * 获取所有支持的文档格式
     */
    public List<String> getSupportedFormats() {
        return List.of("txt", "md", "pdf", "doc", "docx", "html", "htm", "csv", "xls", "xlsx");
    }

}
