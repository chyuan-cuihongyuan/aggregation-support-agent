package cn.chyuan.ai.trigger.support;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 审计上下文工具 — 从 HttpServletRequest 提取 IP / UA，供 Controller 写审计前调用
 * <p>
 * 该类作用是把 servlet API 隔离在 trigger 层，domain 层只接收字符串参数。
 */
public final class AuditContextSupport {

    private static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";
    private static final String HEADER_USER_AGENT = "User-Agent";

    private AuditContextSupport() {
    }

    /**
     * 提取客户端 IP — 优先从 X-Forwarded-For 取第一段，回退 remoteAddr，最后兜底空字符串
     */
    public static String extractIp(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        String forwarded = request.getHeader(HEADER_X_FORWARDED_FOR);
        if (forwarded != null && !forwarded.isEmpty()) {
            int comma = forwarded.indexOf(',');
            String first = (comma >= 0) ? forwarded.substring(0, comma) : forwarded;
            String trimmed = first.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "" : remote;
    }

    /**
     * 提取 User-Agent — 不存在则返回空字符串
     */
    public static String extractUserAgent(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        String ua = request.getHeader(HEADER_USER_AGENT);
        return ua == null ? "" : ua;
    }
}
