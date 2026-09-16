package cn.chyuan.ai.domain.crawler.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 正文抽取去噪（工单 0423 AY5，crawl4ai/firecrawl 正文抽取思想）。
 * HTML 子集：script/style/nav/footer/header 噪声元素整体剪除 → 按块级标签切段 →
 * 逐段文本密度与链接密度评分（score=文本长度×(1-链接密度)）→ 低于阈值剪除 →
 * title + 正文（段间换行）。无 body 或全噪声返回空结果标记。纯函数。
 */
public class ContentExtractor {

    private static final Pattern NOISE = Pattern.compile(
            "(?is)<(script|style|nav|footer|header)\\b[^>]*>.*?</\\1>");
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern BLOCK_SPLIT = Pattern.compile("(?i)</(p|div|h[1-6]|li|section|article|table|blockquote)>");
    private static final Pattern ANCHOR = Pattern.compile("(?is)<a\\b[^>]*>(.*?)</a>");
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");

    /** 抽取结果 */
    public record Extraction(String title, String content, boolean empty) {
    }

    private final int minScore;

    public ContentExtractor(int minScore) {
        this.minScore = Math.max(0, minScore);
    }

    public Extraction extract(String html) {
        if (html == null || html.isBlank()) {
            return new Extraction("", "", true);
        }
        String title = "";
        Matcher titleMatcher = TITLE.matcher(html);
        if (titleMatcher.find()) {
            title = TAGS.matcher(titleMatcher.group(1)).replaceAll("").strip();
        }
        String cleaned = NOISE.matcher(html).replaceAll("");
        Matcher blockMatcher = BLOCK_SPLIT.matcher(cleaned);
        List<String> kept = new ArrayList<>();
        int start = 0;
        while (blockMatcher.find()) {
            String block = cleaned.substring(start, blockMatcher.end());
            start = blockMatcher.end();
            String text = TAGS.matcher(block).replaceAll(" ").replaceAll("\\s+", " ").strip();
            if (text.isEmpty()) {
                continue;
            }
            int linkChars = 0;
            Matcher anchorMatcher = ANCHOR.matcher(block);
            while (anchorMatcher.find()) {
                linkChars += TAGS.matcher(anchorMatcher.group(1)).replaceAll("").length();
            }
            double linkDensity = text.length() == 0 ? 1.0 : (double) linkChars / text.length();
            double score = text.length() * (1 - linkDensity);
            if (score >= minScore) {
                kept.add(text);
            }
        }
        String content = String.join("\n", kept);
        return new Extraction(title, content, content.isEmpty());
    }
}
