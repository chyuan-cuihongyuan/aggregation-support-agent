package cn.chyuan.ai.trigger.filter;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.auth.service.TokenService;
import cn.chyuan.ai.domain.auth.support.RequestScopeContext;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * JwtAuthFilter 鉴权契约测试（工单 1138）：
 * 只断言过滤器对外可观察行为——放行规则、401 响应、请求属性注入、滑动续期 Cookie。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthFilter 鉴权契约")
class JwtAuthFilterTest {

    private static final String TOKEN = "header.payload.signature";

    @Mock
    private TokenService tokenService;

    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthFilter();
        ReflectionTestUtils.setField(filter, "tokenService", tokenService);
        ReflectionTestUtils.setField(filter, "cookieMaxAge", 1209600);
        ReflectionTestUtils.setField(filter, "cookieSecure", true);
        ReflectionTestUtils.setField(filter, "cookieSameSite", "Lax");
        RequestScopeContext.clear();
    }

    @AfterEach
    void tearDown() {
        RequestScopeContext.clear();
    }

    private Claims claimsOf(String userId, String username, String role) {
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(claims.getSubject()).thenReturn(userId);
        when(claims.get("username", String.class)).thenReturn(username);
        when(claims.get("role", String.class)).thenReturn(role);
        when(claims.get("tenantId", String.class)).thenReturn(null);
        when(claims.get("ownerUserId", String.class)).thenReturn(null);
        return claims;
    }

    private MockHttpServletRequest request(String uri, String method, Cookie... cookies) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        if (cookies.length > 0) {
            request.setCookies(cookies);
        }
        return request;
    }

    @Test
    @DisplayName("OPTIONS 预检请求直接放行，不做鉴权")
    void optionsRequestsPassThrough() throws Exception {
        MockHttpServletRequest request = request("/api/v1/secret", "OPTIONS");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(request.getAttribute(JwtAuthFilter.ATTR_USER_ID)).isNull();
    }

    @Test
    @DisplayName("白名单 API 路径放行并注入系统默认用户")
    void whitelistApiPathInjectsSystemUser() throws Exception {
        MockHttpServletRequest request = request("/api/v1/auth/login", "POST");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(request.getAttribute(JwtAuthFilter.ATTR_USER_ID)).isEqualTo(0L);
        assertThat(request.getAttribute(JwtAuthFilter.ATTR_USERNAME)).isEqualTo("eval-system");
    }

    @Test
    @DisplayName("非 /api/ 路径放行且不注入任何用户属性")
    void nonApiPathPassesWithoutAttributes() throws Exception {
        MockHttpServletRequest request = request("/index.html", "GET");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(request.getAttribute(JwtAuthFilter.ATTR_USER_ID)).isNull();
    }

    @Test
    @DisplayName("无 Cookie 的受保护 API 返回 401 与 A0004 契约体，链路被阻断")
    void missingCookieReturns401() throws Exception {
        MockHttpServletRequest request = request("/api/v1/secret", "GET");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("A0004");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("无效 Token 返回 401，链路被阻断")
    void invalidTokenReturns401() throws Exception {
        MockHttpServletRequest request = request("/api/v1/secret", "GET",
                new Cookie("auth_token", TOKEN));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        when(tokenService.validateToken(TOKEN)).thenReturn(false);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Token无效或已过期");
        assertThat(chain.getRequest()).isNull();
    }

    /** 记录链路调用瞬间上下文的 FilterChain：验证链内可见租户域、用户属性已注入。 */
    private static final class RecordingChain implements FilterChain {
        TenantScopeVO scopeSeenInChain;
        Object userIdSeenInChain;
        boolean invoked;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                throws IOException, ServletException {
            invoked = true;
            scopeSeenInChain = RequestScopeContext.get();
            userIdSeenInChain = req.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        }
    }

    @Test
    @DisplayName("有效 Token 注入用户属性，续期成功时下发 Set-Cookie 并透传新 Token")
    void validTokenInjectsAttributesAndRenewsCookie() throws Exception {
        MockHttpServletRequest request = request("/api/v1/secret", "GET",
                new Cookie("auth_token", TOKEN));
        MockHttpServletResponse response = new MockHttpServletResponse();
        Claims claims = claimsOf("42", "alice", "admin");
        when(tokenService.validateToken(TOKEN)).thenReturn(true);
        when(tokenService.parseToken(TOKEN)).thenReturn(claims);
        when(tokenService.renewToken(TOKEN)).thenReturn("renewed-token");
        RecordingChain chain = new RecordingChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.invoked).isTrue();
        assertThat(request.getAttribute(JwtAuthFilter.ATTR_USER_ID)).isEqualTo(42L);
        assertThat(request.getAttribute(JwtAuthFilter.ATTR_USERNAME)).isEqualTo("alice");
        assertThat(request.getAttribute(JwtAuthFilter.ATTR_ROLE)).isEqualTo("admin");
        assertThat(request.getAttribute(JwtAuthFilter.ATTR_AUTH_TOKEN)).isEqualTo("renewed-token");
        assertThat(chain.userIdSeenInChain).isEqualTo(42L);
        String setCookie = response.getHeader("Set-Cookie");
        assertThat(setCookie).contains("auth_token=renewed-token");
        assertThat(setCookie).contains("Max-Age=1209600");
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("Secure");
        assertThat(setCookie).contains("SameSite=Lax");
        // 链内可见租户上下文（claims 无租户字段时回落 userId），链外被清理
        assertThat(chain.scopeSeenInChain).isNotNull();
        assertThat(chain.scopeSeenInChain.getTenantId()).isEqualTo("42");
        assertThat(RequestScopeContext.get()).isNull();
    }

    @Test
    @DisplayName("续期返回 null 时保留原 Token，不下发 Set-Cookie")
    void renewNullKeepsOriginalToken() throws Exception {
        MockHttpServletRequest request = request("/api/v1/secret", "GET",
                new Cookie("auth_token", TOKEN));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        Claims claims = claimsOf("42", "alice", "admin");
        when(tokenService.validateToken(TOKEN)).thenReturn(true);
        when(tokenService.parseToken(TOKEN)).thenReturn(claims);
        when(tokenService.renewToken(TOKEN)).thenReturn(null);

        filter.doFilter(request, response, chain);

        assertThat(request.getAttribute(JwtAuthFilter.ATTR_AUTH_TOKEN)).isEqualTo(TOKEN);
        assertThat(response.getHeader("Set-Cookie")).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }
}
