package cn.chyuan.ai.trigger.filter;

import cn.chyuan.ai.domain.auth.service.TokenService;
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
            request.setAttribute("userId", Long.valueOf(claims.getSubject()));
            request.setAttribute("username", claims.get("username", String.class));
            request.setAttribute("role", claims.get("role", String.class));
        }

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if ("auth_token".equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
