package cn.chyuan.ai.infrastructure.gateway.parser;

import cn.chyuan.ai.domain.rag.adapter.port.IDocumentParser;
import cn.chyuan.ai.domain.rag.model.valobj.ParsedDocumentVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * TXT文档解析器 — 解析纯文本文件
 */
@Slf4j
@Component
public class TxtDocumentParser implements IDocumentParser {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".txt", ".text", ".log", ".csv");

    @Override
    public ParsedDocumentVO parse(byte[] content, String fileName, String mimeType) {
        log.info("解析TXT文档: {}", fileName);

        String textContent = new String(content, StandardCharsets.UTF_8);
        String extension = getFileExtension(fileName);

        // 清洗文本
        String cleanedText = cleanText(textContent);

        // 按段落分割
        List<ParsedDocumentVO.DocumentSection> sections = splitByParagraphs(cleanedText);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("format", "txt");
        metadata.put("encoding", "UTF-8");
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
        if (mimeType != null && (mimeType.startsWith("text/plain") || mimeType.startsWith("text/csv"))) {
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
        // 去除多余空行（保留最多两个连续换行）
        text = text.replaceAll("\n{3,}", "\n\n");
        // 去除行尾空白
        text = text.replaceAll("[ \t]+$", "");
        // 去除首尾空白
        return text.trim();
    }

    /**
     * 按段落分割文本
     */
    private List<ParsedDocumentVO.DocumentSection> splitByParagraphs(String text) {
        List<ParsedDocumentVO.DocumentSection> sections = new ArrayList<>();
        String[] paragraphs = text.split("\n\n+");

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

        return sections;
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
