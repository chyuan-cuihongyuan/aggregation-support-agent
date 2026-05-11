package cn.chyuan.ai.infrastructure.gateway.parser;

import cn.chyuan.ai.domain.rag.adapter.port.IDocumentParser;
import cn.chyuan.ai.domain.rag.model.valobj.ParsedDocumentVO;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * HTML文档解析器 — 解析HTML格式文档
 */
@Slf4j
@Component
public class HtmlDocumentParser implements IDocumentParser {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".html", ".htm", ".xhtml");
    private static final Pattern HEADING_PATTERN = Pattern.compile("^h([1-6])$", Pattern.CASE_INSENSITIVE);

    @Override
    public ParsedDocumentVO parse(byte[] content, String fileName, String mimeType) {
        log.info("解析HTML文档: {}", fileName);

        String extension = getFileExtension(fileName);
        String htmlContent = new String(content, StandardCharsets.UTF_8);

        Document doc = Jsoup.parse(htmlContent);

        // 移除不需要的元素
        doc.select("script, style, nav, footer, header, aside, .sidebar, .menu, .navigation").remove();

        // 提取标题
        String title = doc.title();
        if (title == null || title.isEmpty()) {
            Element titleElement = doc.selectFirst("h1");
            if (titleElement != null) {
                title = titleElement.text();
            }
        }

        // 提取正文内容
        Element body = doc.body();
        if (body == null) {
            body = doc;
        }

        // 解析章节结构
        List<ParsedDocumentVO.DocumentSection> sections = parseSections(body);

        // 提取纯文本
        String cleanedText = cleanText(body.text());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("format", "html");
        metadata.put("encoding", "UTF-8");
        metadata.put("title", title);
        metadata.put("sectionCount", sections.size());
        metadata.put("charCount", cleanedText.length());

        // 提取meta标签信息
        Elements metaTags = doc.select("meta");
        for (Element meta : metaTags) {
            String name = meta.attr("name");
            String contentAttr = meta.attr("content");
            if (!name.isEmpty() && !contentAttr.isEmpty()) {
                metadata.put("meta_" + name, contentAttr);
            }
        }

        return ParsedDocumentVO.builder()
                .fileName(fileName)
                .extension(extension)
                .mimeType(mimeType)
                .textContent(cleanedText)
                .sections(sections)
                .metadata(metadata)
                .build();
    }

    @Override
    public boolean supports(String mimeType, String fileName) {
        if (mimeType != null && mimeType.contains("html")) {
            return true;
        }
        String ext = getFileExtension(fileName);
        return SUPPORTED_EXTENSIONS.contains(ext);
    }

    /**
     * 解析HTML章节结构
     */
    private List<ParsedDocumentVO.DocumentSection> parseSections(Element body) {
        List<ParsedDocumentVO.DocumentSection> sections = new ArrayList<>();

        // 获取所有标题元素
        Elements headings = body.select("h1, h2, h3, h4, h5, h6");

        if (headings.isEmpty()) {
            // 没有标题，按段落分割
            Elements paragraphs = body.select("p, div, article, section");
            int position = 0;
            for (Element p : paragraphs) {
                String text = p.text().trim();
                if (!text.isEmpty() && text.length() > 20) { // 过滤太短的段落
                    sections.add(ParsedDocumentVO.DocumentSection.builder()
                            .title(null)
                            .content(text)
                            .level(0)
                            .position(position++)
                            .build());
                }
            }
        } else {
            // 按标题分割
            int position = 0;
            for (int i = 0; i < headings.size(); i++) {
                Element heading = headings.get(i);
                int level = getHeadingLevel(heading.tagName());
                String headingText = heading.text().trim();

                // 获取标题后的内容
                StringBuilder content = new StringBuilder();
                Element next = heading.nextElementSibling();

                while (next != null) {
                    // 如果遇到下一个标题，停止
                    if (next.tagName().matches("^h[1-6]$")) {
                        break;
                    }
                    String text = next.text().trim();
                    if (!text.isEmpty()) {
                        content.append(text).append("\n");
                    }
                    next = next.nextElementSibling();
                }

                if (content.length() > 0) {
                    sections.add(ParsedDocumentVO.DocumentSection.builder()
                            .title(headingText)
                            .content(cleanText(content.toString()))
                            .level(level)
                            .position(position++)
                            .build());
                }
            }
        }

        // 如果仍然没有内容，提取body文本
        if (sections.isEmpty()) {
            String bodyText = body.text().trim();
            if (!bodyText.isEmpty()) {
                sections.add(ParsedDocumentVO.DocumentSection.builder()
                        .title(null)
                        .content(bodyText)
                        .level(0)
                        .position(0)
                        .build());
            }
        }

        return sections;
    }

    /**
     * 获取标题层级
     */
    private int getHeadingLevel(String tagName) {
        java.util.regex.Matcher matcher = HEADING_PATTERN.matcher(tagName);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return 0;
    }

    /**
     * 清洗文本
     */
    private String cleanText(String text) {
        if (text == null) return "";

        // 去除多余空白
        text = text.replaceAll("\\s+", " ");
        // 去除特殊字符
        text = text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
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
