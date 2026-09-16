package cn.chyuan.ai.domain.crawler.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * URL 归一化与去重指纹（工单 0420 AY2，RFC 3986 子集，crawl4ai/firecrawl URL 口径）。
 * 方案与主机小写/默认端口剥离/路径空段与尾斜杠归一/查询参数排序并去 utm_* 追踪参数/
 * 剥离 fragment；规范化串 SHA-256 指纹；无 scheme 或 host 拒绝。纯函数。
 */
public class UrlNormalizer {

    /** 归一化产物 */
    public record Normalized(String canonical, String fingerprint) {
    }

    public Normalized normalize(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("URL 不能为空");
        }
        String url = rawUrl.strip();
        int schemeEnd = url.indexOf("://");
        if (schemeEnd <= 0) {
            throw new IllegalArgumentException("URL 缺少 scheme: " + rawUrl);
        }
        String scheme = url.substring(0, schemeEnd).toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("仅支持 http/https: " + rawUrl);
        }
        String rest = url.substring(schemeEnd + 3);
        // 剥离 fragment
        int fragment = rest.indexOf('#');
        if (fragment >= 0) {
            rest = rest.substring(0, fragment);
        }
        // authority 与 path/query 分离
        String authority = rest;
        String pathAndQuery = "/";
        int slash = rest.indexOf('/');
        int quest = rest.indexOf('?');
        int cut = slash >= 0 && (quest < 0 || slash < quest) ? slash : quest;
        if (cut >= 0) {
            authority = rest.substring(0, cut);
            pathAndQuery = rest.substring(cut);
        }
        if (authority.isEmpty()) {
            throw new IllegalArgumentException("URL 缺少 host: " + rawUrl);
        }
        String host = authority.toLowerCase();
        int at = host.lastIndexOf('@');
        if (at >= 0) {
            host = host.substring(at + 1);
        }
        int port = -1;
        int colon = host.lastIndexOf(':');
        if (colon > 0 && !host.substring(colon + 1).isEmpty() && host.charAt(0) != '[') {
            host = host.substring(0, colon);
            try {
                port = Integer.parseInt(authority.substring(authority.lastIndexOf(':') + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("非法端口: " + rawUrl);
            }
        }
        if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
            port = -1;
        }
        // path 归一：空段折叠 + 尾斜杠去除（根 "/" 保留）
        String path = pathAndQuery;
        int q = path.indexOf('?');
        String query = "";
        if (q >= 0) {
            query = path.substring(q + 1);
            path = path.substring(0, q);
        }
        path = path.replaceAll("/{2,}", "/");
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty()) {
            path = "/";
        }
        String canonicalQuery = normalizeQuery(query);
        String authorityCanonical = host + (port > 0 ? ":" + port : "");
        String canonical = scheme + "://" + authorityCanonical + path
                + (canonicalQuery.isEmpty() ? "" : "?" + canonicalQuery);
        return new Normalized(canonical, sha256(canonical));
    }

    /** 查询参数排序 + 去 utm_* 追踪参数 + 去空值参数 */
    private String normalizeQuery(String query) {
        if (query.isEmpty()) {
            return "";
        }
        List<String[]> params = new ArrayList<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            if (name.toLowerCase().startsWith("utm_") || value.isEmpty()) {
                continue;
            }
            params.add(new String[]{name, value});
        }
        params.sort(Comparator.comparing(p -> p[0]));
        StringBuilder sb = new StringBuilder();
        for (String[] param : params) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(param[0]).append('=').append(param[1]);
        }
        return sb.toString();
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
