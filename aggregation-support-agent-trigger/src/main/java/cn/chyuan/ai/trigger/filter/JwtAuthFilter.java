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
import org.springframework.beans.factory.annotation.Value;
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
    public static final String REQUEST_ATTR_JWT = "authToken";

    private static final String COOKIE_NAME = "auth_token";

    @Resource
    private TokenService tokenService;

    @Value("${auth.cookie.max-age}")
    private int cookieMaxAge;

    @Value("${auth.cookie.secure}")
    private boolean cookieSecure;

    @Value("${auth.cookie.same-site}")
    private String cookieSameSite;

    private static final Set<String> WHITE_LIST = new HashSet<>(Arrays.asList(
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/chat"
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
            // 为白名单 API 路径设置系统默认用户，供控制器获取 userId 不报错
            if (path.startsWith("/api/")) {
                request.setAttribute(ATTR_USER_ID, 0L);
                request.setAttribute(ATTR_USERNAME, "eval-system");
            }
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

            // 滑动续期：复用 jti 重签 JWT（新 exp）+ 重置 Redis TTL + 刷新 Cookie Max-Age。
            // 活跃用户每次请求都会把会话窗口推后；20 分钟内无请求则 Redis 过期 → 下次 401。
            // 注意：logout 接口的 Set-Cookie(max-age=0) 会在 Controller 阶段覆盖此处的续期，登出仍可正常清除。
            String renewedToken = tokenService.renewToken(token);
            if (renewedToken != null) {
                response.setHeader("Set-Cookie", buildCookieHeader(renewedToken));
                request.setAttribute(REQUEST_ATTR_JWT, renewedToken);
            } else {
                request.setAttribute(REQUEST_ATTR_JWT, token);
            }

            RequestScopeContext.set(resolveTenantScope(claims, String.valueOf(userId)));
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestScopeContext.clear();
        }
    }

    private String extractToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * 构建续期用的 Set-Cookie 头（与 AuthController 保持一致）。
     * JwtAuthFilter 在每次请求校验通过后调用，刷新 Max-Age 实现滑动过期。
     */
    private String buildCookieHeader(String token) {
        StringBuilder header = new StringBuilder()
                .append(COOKIE_NAME).append("=").append(token)
                .append("; Path=/")
                .append("; Max-Age=").append(cookieMaxAge)
                .append("; HttpOnly");
        if (cookieSecure) {
            header.append("; Secure");
        }
        if (cookieSameSite != null && !cookieSameSite.isBlank()) {
            header.append("; SameSite=").append(cookieSameSite);
        }
        return header.toString();
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
