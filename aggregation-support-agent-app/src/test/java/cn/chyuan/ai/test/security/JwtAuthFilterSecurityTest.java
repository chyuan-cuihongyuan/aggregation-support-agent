package cn.chyuan.ai.test.security;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.auth.adapter.repository.ITokenRepository;
import cn.chyuan.ai.domain.auth.service.TokenService;
import cn.chyuan.ai.domain.auth.support.RequestScopeContext;
import cn.chyuan.ai.trigger.config.AuthCookieProperties;
import cn.chyuan.ai.trigger.filter.JwtAuthFilter;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.After;
import org.junit.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class JwtAuthFilterSecurityTest {

    private final StubTokenService tokenService = new StubTokenService();
    private final JwtAuthFilter filter = new JwtAuthFilter();

    public JwtAuthFilterSecurityTest() {
        ReflectionTestUtils.setField(filter, "tokenService", tokenService);
        ReflectionTestUtils.setField(filter, "authCookieProperties", new AuthCookieProperties());
    }

    @After
    public void tearDown() {
        RequestScopeContext.clear();
    }

    @Test
    public void doFilter_withValidLoginCookie_allowsProtectedRequestAndSetsUserScope() throws Exception {
        String token = "valid-token";
        Claims claims = Jwts.claims()
                .setSubject("101")
                .setIssuedAt(new Date());
        claims.put("username", "alice");
        claims.put("role", "user");
        tokenService.addValidToken(token, claims);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/user/info");
        request.setCookies(new Cookie("auth_token", token));
        MockHttpServletResponse response = new MockHttpServletResponse();
        CapturingFilterChain chain = new CapturingFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertTrue(chain.invoked);
        assertSame(request, chain.request);
        assertEquals(101L, request.getAttribute(JwtAuthFilter.ATTR_USER_ID));
        assertEquals("alice", request.getAttribute(JwtAuthFilter.ATTR_USERNAME));
        assertEquals("user", request.getAttribute(JwtAuthFilter.ATTR_ROLE));
        assertEquals(token, request.getAttribute(JwtAuthFilter.ATTR_AUTH_TOKEN));
        TenantScopeVO scopeSeenByController = chain.scope;
        assertEquals("101", scopeSeenByController.getTenantId());
        assertEquals("101", scopeSeenByController.getOwnerUserId());
        assertNull(RequestScopeContext.get());
    }

    @Test
    public void doFilter_withRevokedTokenRejectsRequest() throws Exception {
        String token = "revoked-token";

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/user/info");
        request.setCookies(new Cookie("auth_token", token));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("A0004"));
        assertEquals(1, tokenService.validateTokenCalls);
        assertNull(RequestScopeContext.get());
    }

    private static class StubTokenService extends TokenService {
        private final Map<String, Claims> claimsByToken = new HashMap<>();
        private int validateTokenCalls;

        private StubTokenService() {
            super(new NoopTokenRepository());
        }

        private void addValidToken(String token, Claims claims) {
            claimsByToken.put(token, claims);
        }

        @Override
        public boolean validateToken(String token) {
            validateTokenCalls++;
            return claimsByToken.containsKey(token);
        }

        @Override
        public Claims parseToken(String token) {
            return claimsByToken.get(token);
        }
    }

    private static class NoopTokenRepository implements ITokenRepository {
        @Override
        public void saveToken(String key, String token, long expireSeconds) {
        }

        @Override
        public String queryToken(String key) {
            return null;
        }

        @Override
        public void removeToken(String key) {
        }
    }

    private static class CapturingFilterChain extends MockFilterChain {
        private boolean invoked;
        private ServletRequest request;
        private TenantScopeVO scope;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            this.invoked = true;
            this.request = request;
            this.scope = RequestScopeContext.get();
        }
    }
}
