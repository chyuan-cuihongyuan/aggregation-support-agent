package cn.chyuan.ai.infrastructure.gateway.parser;

import cn.chyuan.ai.domain.rag.adapter.port.IDocumentParser;
import cn.chyuan.ai.domain.rag.model.valobj.ParsedDocumentVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * PDF文档解析器 — 解析PDF格式文档
 */
@Slf4j
@Component
public class PdfDocumentParser implements IDocumentParser {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".pdf");
    private static final Pattern PAGE_BREAK_PATTERN = Pattern.compile("\\f");

    @Override
    public ParsedDocumentVO parse(byte[] content, String fileName, String mimeType) {
        log.info("解析PDF文档: {}", fileName);

        String extension = getFileExtension(fileName);

        try (PDDocument document = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            String rawText = stripper.getText(document);
            int pageCount = document.getNumberOfPages();

            // 按页分割
            List<ParsedDocumentVO.DocumentSection> sections = new ArrayList<>();
            String[] pages = PAGE_BREAK_PATTERN.split(rawText);

            for (int i = 0; i < pages.length; i++) {
                String pageContent = pages[i].trim();
                if (!pageContent.isEmpty()) {
                    sections.add(ParsedDocumentVO.DocumentSection.builder()
                            .title("第 " + (i + 1) + " 页")
                            .content(cleanText(pageContent))
                            .level(1)
                            .position(i)
                            .build());
                }
            }

            // 清洗全文
            String cleanedText = cleanText(rawText);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("format", "pdf");
            metadata.put("pageCount", pageCount);
            metadata.put("charCount", cleanedText.length());

            // 提取PDF元数据
            if (document.getDocumentInformation() != null) {
                String title = document.getDocumentInformation().getTitle();
                String author = document.getDocumentInformation().getAuthor();
                if (title != null) metadata.put("pdfTitle", title);
                if (author != null) metadata.put("pdfAuthor", author);
            }

            return ParsedDocumentVO.builder()
                    .fileName(fileName)
                    .extension(extension)
                    .mimeType(mimeType)
                    .textContent(cleanedText)
                    .sections(sections)
                    .metadata(metadata)
                    .build();

        } catch (IOException e) {
            log.error("PDF解析失败: {}", fileName, e);
            throw new RuntimeException("PDF解析失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean supports(String mimeType, String fileName) {
        if (mimeType != null && mimeType.equals("application/pdf")) {
            return true;
        }
        String ext = getFileExtension(fileName);
        return SUPPORTED_EXTENSIONS.contains(ext);
    }

    /**
     * 清洗文本 — 去除多余空白、规范化换行符
     */
    private String cleanText(String text) {
        if (text == null) return "";

        // 替换Windows换行符
        text = text.replace("\r\n", "\n");
        // 去除PDF常见噪声字符
        text = text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
        // 去除多余空行
        text = text.replaceAll("\n{3,}", "\n\n");
        // 去除行尾空白
        text = text.replaceAll("[ \\t]+$", "");
        // 去除首尾空白
        return text.trim();
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String fileName) {
        if (fileName == null) return "";
        int lastDot = fileName.lastIndexOf('.');
        return lastDot >= 0 ? fileName.substring(lastDot).toLowerCase() : "";
    }

}
