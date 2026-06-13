package cn.chyuan.ai.trigger.filter;

import cn.chyuan.ai.domain.auth.service.TokenService;
import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.auth.support.RequestScopeContext;
import io.jsonwebtoken.Claims;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Component
@Order(1)
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String ATTR_USER_ID = "userId";
    public static final String ATTR_USERNAME = "username";
    public static final String ATTR_ROLE = "role";
    public static final String ATTR_AUTH_TOKEN = "authToken";

    @Resource
    private TokenService tokenService;

    private static final Set<String> WHITE_LIST = new HashSet<>(Arrays.asList(
            "/api/v1/auth/register",
            "/api/v1/auth/login"
    ));

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();

        // 跳过 OPTIONS 预检请求（CORS）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        // 白名单和非API路径放行
        if (WHITE_LIST.contains(path) || !path.startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }

        // 从 Cookie 中读取 Token
        String token = extractToken(request);
        if (token == null || !tokenService.validateToken(token)) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"A0004\",\"info\":\"Token无效或已过期\",\"data\":null}");
            return;
        }

        // 解析用户信息放入 Request Attribute
        Claims claims = tokenService.parseToken(token);
        if (claims != null) {
            Long userId = Long.valueOf(claims.getSubject());
            request.setAttribute(ATTR_USER_ID, userId);
            request.setAttribute(ATTR_USERNAME, claims.get("username", String.class));
            request.setAttribute(ATTR_ROLE, claims.get("role", String.class));
            request.setAttribute(ATTR_AUTH_TOKEN, token);
            RequestScopeContext.set(resolveTenantScope(claims, String.valueOf(userId)));
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestScopeContext.clear();
        }
    }

    private String extractToken(HttpServletRequest request) {
        // 优先从 Cookie 读取（浏览器前端场景）
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("auth_token".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        // 兜底从 Authorization 头读取（服务间调用/MCP 场景，如 mcp-gateway 经 SSE 调用本服务）。
        // 原先只读 Cookie 导致所有经 Authorization: Bearer 传 JWT 的调用一律 401（A0004 Token无效或已过期）。
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring("Bearer ".length()).trim();
        }
        return null;
    }

    private TenantScopeVO resolveTenantScope(Claims claims, String fallbackUserId) {
        String tenantId = claims.get("tenantId", String.class);
        String ownerUserId = claims.get("ownerUserId", String.class);
        return TenantScopeVO.builder()
                .tenantId(isBlank(tenantId) ? fallbackUserId : tenantId)
                .ownerUserId(isBlank(ownerUserId) ? fallbackUserId : ownerUserId)
                .build();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
