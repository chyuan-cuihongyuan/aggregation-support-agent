package cn.chyuan.ai.test.security;

import cn.chyuan.ai.domain.auth.adapter.repository.ITokenRepository;
import cn.chyuan.ai.domain.auth.service.TokenService;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TokenServiceLogoutSecurityTest {

    @Test
    public void removeTokenRevokesPreviouslyValidToken() {
        InMemoryTokenRepository tokenRepository = new InMemoryTokenRepository();
        TokenService tokenService = new TokenService(tokenRepository);
        ReflectionTestUtils.setField(tokenService, "secret", Base64.getEncoder()
                .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        ReflectionTestUtils.setField(tokenService, "expiration", 3600_000L);

        String token = tokenService.generateToken(101L, "alice", "user");

        assertTrue(tokenService.validateToken(token));

        tokenService.removeToken(token);

        assertFalse(tokenService.validateToken(token));
    }

    private static class InMemoryTokenRepository implements ITokenRepository {
        private final Map<String, String> tokens = new HashMap<>();

        @Override
        public void saveToken(String key, String token, long expireSeconds) {
            tokens.put(key, token);
        }

        @Override
        public String queryToken(String key) {
            return tokens.get(key);
        }

        @Override
        public void removeToken(String key) {
            tokens.remove(key);
        }
    }
}
