package cn.chyuan.ai.infrastructure.gateway.parser;

import cn.chyuan.ai.domain.rag.adapter.port.IDocumentParser;
import cn.chyuan.ai.domain.rag.model.valobj.ParsedDocumentVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Word文档解析器 — 解析Word格式文档（.doc和.docx）
 */
@Slf4j
@Component
public class WordDocumentParser implements IDocumentParser {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".doc", ".docx");
    private static final Pattern HEADING_PATTERN = Pattern.compile("^Heading\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    @Override
    public ParsedDocumentVO parse(byte[] content, String fileName, String mimeType) {
        log.info("解析Word文档: {}", fileName);

        String extension = getFileExtension(fileName);

        try {
            if (extension.equals(".docx")) {
                return parseDocx(content, fileName, extension, mimeType);
            } else if (extension.equals(".doc")) {
                return parseDoc(content, fileName, extension, mimeType);
            } else {
                throw new IllegalArgumentException("不支持的Word格式: " + extension);
            }
        } catch (IOException e) {
            log.error("Word文档解析失败: {}", fileName, e);
            throw new RuntimeException("Word文档解析失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean supports(String mimeType, String fileName) {
        if (mimeType != null && (mimeType.contains("msword") || mimeType.contains("wordprocessingml"))) {
            return true;
        }
        String ext = getFileExtension(fileName);
        return SUPPORTED_EXTENSIONS.contains(ext);
    }

    /**
     * 解析.docx格式
     */
    private ParsedDocumentVO parseDocx(byte[] content, String fileName, String extension, String mimeType) throws IOException {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(content);
             XWPFDocument document = new XWPFDocument(bis)) {

            List<ParsedDocumentVO.DocumentSection> sections = new ArrayList<>();
            StringBuilder fullText = new StringBuilder();
            int position = 0;

            // 按段落解析
            String currentTitle = null;
            int currentLevel = 0;
            StringBuilder currentContent = new StringBuilder();

            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph) {
                    XWPFParagraph paragraph = (XWPFParagraph) element;
                    String text = paragraph.getText().trim();

                    if (text.isEmpty()) continue;

                    String styleName = paragraph.getStyle();
                    int headingLevel = getHeadingLevel(styleName);

                    if (headingLevel > 0) {
                        // 保存前一个章节
                        if (currentContent.length() > 0) {
                            sections.add(ParsedDocumentVO.DocumentSection.builder()
                                    .title(currentTitle)
                                    .content(cleanText(currentContent.toString()))
                                    .level(currentLevel)
                                    .position(position++)
                                    .build());
                            currentContent = new StringBuilder();
                        }
                        currentTitle = text;
                        currentLevel = headingLevel;
                    } else {
                        currentContent.append(text).append("\n");
                        fullText.append(text).append("\n");
                    }
                } else if (element instanceof XWPFTable) {
                    XWPFTable table = (XWPFTable) element;
                    String tableText = extractTableText(table);
                    currentContent.append(tableText).append("\n");
                    fullText.append(tableText).append("\n");
                }
            }

            // 保存最后一个章节
            if (currentContent.length() > 0) {
                sections.add(ParsedDocumentVO.DocumentSection.builder()
                        .title(currentTitle)
                        .content(cleanText(currentContent.toString()))
                        .level(currentLevel)
                        .position(position)
                        .build());
            }

            String cleanedText = cleanText(fullText.toString());

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("format", "docx");
            metadata.put("sectionCount", sections.size());
            metadata.put("charCount", cleanedText.length());

            return ParsedDocumentVO.builder()
                    .fileName(fileName)
                    .extension(extension)
                    .mimeType(mimeType)
                    .textContent(cleanedText)
                    .sections(sections)
                    .metadata(metadata)
                    .build();
        }
    }

    /**
     * 解析.doc格式
     */
    private ParsedDocumentVO parseDoc(byte[] content, String fileName, String extension, String mimeType) throws IOException {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(content);
             HWPFDocument document = new HWPFDocument(bis);
             WordExtractor extractor = new WordExtractor(document)) {

            String rawText = extractor.getText();
            String cleanedText = cleanText(rawText);

            // .doc格式难以精确提取标题结构，按段落分割
            List<ParsedDocumentVO.DocumentSection> sections = new ArrayList<>();
            String[] paragraphs = cleanedText.split("\n\n+");

            int position = 0;
            for (String paragraph : paragraphs) {
                String trimmed = paragraph.trim();
                if (!trimmed.isEmpty()) {
                    sections.add(ParsedDocumentVO.DocumentSection.builder()
                            .title(null)
                            .content(trimmed)
                            .level(0)
                            .position(position++)
                            .build());
                }
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("format", "doc");
            metadata.put("sectionCount", sections.size());
            metadata.put("charCount", cleanedText.length());

            return ParsedDocumentVO.builder()
                    .fileName(fileName)
                    .extension(extension)
                    .mimeType(mimeType)
                    .textContent(cleanedText)
                    .sections(sections)
                    .metadata(metadata)
                    .build();
        }
    }

    /**
     * 提取表格文本
     */
    private String extractTableText(XWPFTable table) {
        StringBuilder sb = new StringBuilder();
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                sb.append(cell.getText()).append("\t");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 获取标题层级
     */
    private int getHeadingLevel(String styleName) {
        if (styleName == null) return 0;

        java.util.regex.Matcher matcher = HEADING_PATTERN.matcher(styleName);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        // 检查常见标题样式
        String lower = styleName.toLowerCase();
        if (lower.contains("heading 1") || lower.equals("title")) return 1;
        if (lower.contains("heading 2") || lower.equals("subtitle")) return 2;
        if (lower.contains("heading 3")) return 3;
        if (lower.contains("heading 4")) return 4;
        if (lower.contains("heading 5")) return 5;
        if (lower.contains("heading 6")) return 6;

        return 0;
    }

    /**
     * 清洗文本
     */
    private String cleanText(String text) {
        if (text == null) return "";

        text = text.replace("\r\n", "\n");
        text = text.replaceAll("\n{3,}", "\n\n");
        text = text.replaceAll("[ \\t]+$", "");
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
