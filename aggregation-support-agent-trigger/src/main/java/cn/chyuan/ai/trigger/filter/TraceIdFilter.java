package cn.chyuan.ai.trigger.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * HTTP 入口 traceId 过滤器（D08，借鉴 OTel W3C trace-context 头透传语义）
 *
 * <p>MDC key 用 "trace-id"——与 logback-spring.xml 既有 pattern（%X{trace-id}）对齐，
 * 该 pattern 此前无写入方（孤儿输出位），本过滤器补齐写入侧。</p>
 *
 * <p>每个请求：优先透传上游 X-Trace-Id 头，否则自生成；写 MDC 并回写响应头；
 * 结束清理防线程池串号。顺序先于 JwtAuthFilter，鉴权日志同带 traceId。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    public static final String MDC_TRACE_ID = "trace-id";
    private static final int MAX_TRACE_ID_LEN = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = sanitize(request.getHeader(HEADER_TRACE_ID));
        if (traceId == null) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        MDC.put(MDC_TRACE_ID, traceId);
        response.setHeader(HEADER_TRACE_ID, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID);
        }
    }

    /**
     * 上游头只放行字母数字与短横线（≤64），防日志注入（换行/控制字符伪造日志行）
     */
    private String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_TRACE_ID_LEN) {
            trimmed = trimmed.substring(0, MAX_TRACE_ID_LEN);
        }
        return trimmed.matches("[A-Za-z0-9\\-]+") ? trimmed : null;
    }
}
