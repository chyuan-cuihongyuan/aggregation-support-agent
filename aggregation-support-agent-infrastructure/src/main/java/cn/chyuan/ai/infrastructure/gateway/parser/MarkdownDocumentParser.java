package cn.chyuan.ai.infrastructure.gateway.parser;

import cn.chyuan.ai.domain.rag.adapter.port.IDocumentParser;
import cn.chyuan.ai.domain.rag.model.valobj.ParsedDocumentVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown文档解析器 — 解析Markdown格式文档，保留标题结构
 */
@Slf4j
@Component
public class MarkdownDocumentParser implements IDocumentParser {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".md", ".markdown", ".mdown", ".mkd");
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```[\\s\\S]*?```", Pattern.MULTILINE);
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[([^\\]]+)\\]\\([^\\)]+\\)");
    private static final Pattern IMAGE_PATTERN = Pattern.compile("!\\[([^\\]]*)\\]\\([^\\)]+\\)");
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*([^\\*]+)\\*\\*|__([^_]+)__");
    private static final Pattern ITALIC_PATTERN = Pattern.compile("\\*([^\\*]+)\\*|_([^_]+)_");

    @Override
    public ParsedDocumentVO parse(byte[] content, String fileName, String mimeType) {
        log.info("解析Markdown文档: {}", fileName);

        String rawContent = new String(content, StandardCharsets.UTF_8);
        String extension = getFileExtension(fileName);

        // 提取代码块（保留占位符）
        Map<String, String> codeBlocks = new HashMap<>();
        String processedContent = extractCodeBlocks(rawContent, codeBlocks);

        // 解析章节结构
        List<ParsedDocumentVO.DocumentSection> sections = parseSections(processedContent);

        // 清洗Markdown格式
        String cleanedText = cleanMarkdown(processedContent, codeBlocks);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("format", "markdown");
        metadata.put("encoding", "UTF-8");
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

    @Override
    public boolean supports(String mimeType, String fileName) {
        if (mimeType != null && (mimeType.contains("markdown") || mimeType.contains("x-markdown"))) {
            return true;
        }
        String ext = getFileExtension(fileName);
        return SUPPORTED_EXTENSIONS.contains(ext);
    }

    /**
     * 提取代码块，用占位符替换
     */
    private String extractCodeBlocks(String content, Map<String, String> codeBlocks) {
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(content);
        StringBuffer sb = new StringBuffer();
        int index = 0;

        while (matcher.find()) {
            String placeholder = "§CODE_BLOCK_" + index + "§";
            codeBlocks.put(placeholder, matcher.group());
            matcher.appendReplacement(sb, Matcher.quoteReplacement(placeholder));
            index++;
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    /**
     * 解析Markdown章节结构
     */
    private List<ParsedDocumentVO.DocumentSection> parseSections(String content) {
        List<ParsedDocumentVO.DocumentSection> sections = new ArrayList<>();
        Matcher matcher = HEADING_PATTERN.matcher(content);

        int lastEnd = 0;
        String currentTitle = null;
        int currentLevel = 0;
        int position = 0;

        while (matcher.find()) {
            // 保存前一个章节的内容
            if (lastEnd < matcher.start()) {
                String sectionContent = content.substring(lastEnd, matcher.start()).trim();
                if (!sectionContent.isEmpty()) {
                    sections.add(ParsedDocumentVO.DocumentSection.builder()
                            .title(currentTitle)
                            .content(sectionContent)
                            .level(currentLevel)
                            .position(position++)
                            .build());
                }
            }

            // 更新当前标题
            currentLevel = matcher.group(1).length();
            currentTitle = matcher.group(2).trim();
            lastEnd = matcher.start();
        }

        // 添加最后一个章节
        if (lastEnd < content.length()) {
            String sectionContent = content.substring(lastEnd).trim();
            if (!sectionContent.isEmpty()) {
                sections.add(ParsedDocumentVO.DocumentSection.builder()
                        .title(currentTitle)
                        .content(sectionContent)
                        .level(currentLevel)
                        .position(position)
                        .build());
            }
        }

        // 如果没有找到标题，整个文档作为一个章节
        if (sections.isEmpty() && !content.trim().isEmpty()) {
            sections.add(ParsedDocumentVO.DocumentSection.builder()
                    .title(null)
                    .content(content.trim())
                    .level(0)
                    .position(0)
                    .build());
        }

        return sections;
    }

    /**
     * 清洗Markdown格式，恢复代码块
     */
    private String cleanMarkdown(String content, Map<String, String> codeBlocks) {
        String cleaned = content;

        // 恢复代码块
        for (Map.Entry<String, String> entry : codeBlocks.entrySet()) {
            cleaned = cleaned.replace(entry.getKey(), entry.getValue());
        }

        // 移除图片（保留alt文本）
        cleaned = IMAGE_PATTERN.matcher(cleaned).replaceAll("$1");

        // 移除链接（保留链接文本）
        cleaned = LINK_PATTERN.matcher(cleaned).replaceAll("$1");

        // 移除加粗标记
        cleaned = BOLD_PATTERN.matcher(cleaned).replaceAll(m -> {
            return Matcher.quoteReplacement(m.group(1) != null ? m.group(1) : m.group(2));
        });

        // 移除斜体标记
        cleaned = ITALIC_PATTERN.matcher(cleaned).replaceAll(m -> {
            return Matcher.quoteReplacement(m.group(1) != null ? m.group(1) : m.group(2));
        });

        // 移除行内代码
        cleaned = cleaned.replaceAll("`([^`]+)`", "$1");

        // 移除水平线
        cleaned = cleaned.replaceAll("^[-*_]{3,}\\s*$", "");

        // 移除引用标记
        cleaned = cleaned.replaceAll("^>\\s*", "");

        // 规范化空白
        cleaned = cleaned.replaceAll("\n{3,}", "\n\n");
        cleaned = cleaned.replaceAll("[ \\t]+$", "");

        return cleaned.trim();
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
