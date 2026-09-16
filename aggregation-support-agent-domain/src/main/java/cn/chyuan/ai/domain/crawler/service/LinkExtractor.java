package cn.chyuan.ai.domain.crawler.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 链接抽取（工单 0424 AY6）。
 * a[href] 提取 → 相对路径按 base URL 解析绝对化（./ ../ / 根相对 // 协议相对）→
 * mailto/javascript/#anchor 过滤 → UrlNormalizer 归一去重保序输出。
 */
public class LinkExtractor {

    private static final Pattern HREF = Pattern.compile(
            "(?is)<a\\b[^>]*\\shref\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");

    private final UrlNormalizer normalizer;

    public LinkExtractor(UrlNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    /** 从 html 抽取绝对化且去重保序的链接清单 */
    public List<String> extract(String html, String baseUrl) {
        UrlNormalizer.Normalized base = normalizer.normalize(baseUrl);
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = HREF.matcher(html == null ? "" : html);
        while (matcher.find()) {
            String href = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            String resolved = resolve(href, base.canonical());
            if (resolved == null) {
                continue;
            }
            try {
                seen.add(normalizer.normalize(resolved).canonical());
            } catch (IllegalArgumentException ignored) {
                // 解析后仍非法的链接丢弃
            }
        }
        return new ArrayList<>(seen);
    }

    /** 相对路径解析；mailto/javascript/#/空 返回 null 表示过滤 */
    static String resolve(String href, String baseUrl) {
        if (href == null) {
            return null;
        }
        String link = href.strip();
        if (link.isEmpty() || link.startsWith("#") || link.toLowerCase().startsWith("mailto:")
                || link.toLowerCase().startsWith("javascript:")) {
            return null;
        }
        if (link.matches("(?i)^https?://.*")) {
            return link;
        }
        int schemeEnd = baseUrl.indexOf("://");
        String originPart = baseUrl.substring(0, baseUrl.indexOf('/', schemeEnd + 3));
        String basePath = baseUrl.substring(originPart.length());
        String baseDir = basePath.substring(0, basePath.lastIndexOf('/') + 1);
        if (link.startsWith("//")) {
            return baseUrl.substring(0, schemeEnd) + ":" + link;
        }
        if (link.startsWith("/")) {
            return originPart + link;
        }
        // 相对路径逐段解析 ./ ../
        String[] segments = link.split("/");
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        for (String segment : baseDir.split("/")) {
            if (!segment.isEmpty()) {
                stack.push(segment);
            }
        }
        String query = "";
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                stack.poll();
                continue;
            }
            // 查询串从出现 ? 起原样保留
            if (segment.contains("?")) {
                query = link.substring(link.indexOf(segment) + segment.indexOf('?'));
                break;
            }
            stack.push(segment);
        }
        StringBuilder path = new StringBuilder("/");
        for (java.util.Iterator<String> it = stack.descendingIterator(); it.hasNext(); ) {
            path.append(it.next());
            if (it.hasNext()) {
                path.append('/');
            }
        }
        return originPart + path + (query.isEmpty() ? "" : (query.startsWith("?") ? query : "?" + query));
    }
}
