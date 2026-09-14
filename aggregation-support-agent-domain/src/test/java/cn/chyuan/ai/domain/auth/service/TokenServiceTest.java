package cn.chyuan.ai.domain.auth.service;

import cn.chyuan.ai.domain.auth.adapter.repository.ITokenRepository;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

/**
 * TokenService JWT 契约测试（工单 1138）：
 * 签发→解析→校验→续期→注销全链路（仓储用内存 Map 模拟 Redis 语义）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("TokenService JWT 契约")
class TokenServiceTest {

    /** HS256 要求密钥 ≥32 字节；与生产 application 模板同量级。 */
    private static final String SECRET = "unit-test-signing-secret-0123456789abcdef";
    private static final long EXPIRATION_MS = TimeUnit.HOURS.toMillis(2);

    @Mock
    private ITokenRepository tokenRepository;

    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService(tokenRepository);
        ReflectionTestUtils.setField(tokenService, "secret", SECRET);
        ReflectionTestUtils.setField(tokenService, "expiration", EXPIRATION_MS);
    }

    /** 内存版仓储：saveToken 写入、queryToken 命中、removeToken 删除（Redis 语义）。 */
    private void repositoryBackedByMap(ConcurrentHashMap<String, String> store) {
        org.mockito.Mockito.doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(tokenRepository).saveToken(anyString(), anyString(), anyLong());
        org.mockito.Mockito.when(tokenRepository.queryToken(anyString()))
                .thenAnswer(inv -> store.get(inv.getArgument(0)));
        org.mockito.Mockito.doAnswer(inv -> {
            store.remove(inv.getArgument(0));
            return null;
        }).when(tokenRepository).removeToken(anyString());
    }

    @Test
    @DisplayName("generateToken 落库 TTL=秒级过期时间，Token 解析回用户三要素")
    void generateTokenPersistsAndParses() {
        String token = tokenService.generateToken(42L, "alice", "admin");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);
        verify(tokenRepository).saveToken(keyCaptor.capture(), org.mockito.ArgumentMatchers.eq(token),
                ttlCaptor.capture());
        assertThat(keyCaptor.getValue()).startsWith("auth:token:42:");
        assertThat(ttlCaptor.getValue()).isEqualTo(EXPIRATION_MS / 1000);

        Claims claims = tokenService.parseToken(token);
        assertThat(claims).isNotNull();
        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get("username", String.class)).isEqualTo("alice");
        assertThat(claims.get("role", String.class)).isEqualTo("admin");
        assertThat(tokenService.getUserIdFromToken(token)).isEqualTo(42L);
    }

    @Test
    @DisplayName("默认角色重载签发 user 角色 Token")
    void defaultRoleIsUser() {
        String token = tokenService.generateToken(7L, "bob");

        assertThat(tokenService.parseToken(token).get("role", String.class)).isEqualTo("user");
    }

    @Test
    @DisplayName("validateToken 以仓储命中为准：命中 true、未命中 false")
    void validateFollowsRepositoryHit() {
        ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();
        repositoryBackedByMap(store);

        String token = tokenService.generateToken(1L, "carol");
        assertThat(tokenService.validateToken(token)).isTrue();

        store.clear();
        assertThat(tokenService.validateToken(token)).isFalse();
    }

    @Test
    @DisplayName("伪造/畸形 Token 校验直接拒绝")
    void forgedTokensRejected() {
        assertThat(tokenService.validateToken("not-a-jwt")).isFalse();
        assertThat(tokenService.validateToken("")).isFalse();

        // 用不同密钥签发的 Token（签名不符）→ 拒绝
        TokenService attacker = new TokenService(tokenRepository);
        ReflectionTestUtils.setField(attacker, "secret", "attacker-secret-0123456789abcdefghij");
        ReflectionTestUtils.setField(attacker, "expiration", EXPIRATION_MS);
        String forged = attacker.generateToken(1L, "mallory");
        assertThat(tokenService.validateToken(forged)).isFalse();
        assertThat(tokenService.parseToken(forged)).isNull();
    }

    @Test
    @DisplayName("renewToken 复用 jti 保持 Redis key 不变，新 Token 可继续校验")
    void renewKeepsJtiAndRedisKey() {
        ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();
        repositoryBackedByMap(store);

        String original = tokenService.generateToken(9L, "dave");
        String redisKeyBefore = List.copyOf(store.keySet()).get(0);

        String renewed = tokenService.renewToken(original);

        // 同毫秒重签可能字节级相同（jjwt exp 按秒序列化）；契约点在 jti 复用与同 key 覆盖
        Claims renewedClaims = tokenService.parseToken(renewed);
        assertThat(renewedClaims).isNotNull();
        assertThat(renewedClaims.getId()).isEqualTo(tokenService.parseToken(original).getId());
        // 同 key 覆盖（滑动过期语义），仍能通过校验
        assertThat(tokenService.validateToken(renewed)).isTrue();
        assertThat(tokenService.getUserIdFromToken(renewed)).isEqualTo(9L);
        assertThat(redisKeyBefore).startsWith("auth:token:9:");
    }

    @Test
    @DisplayName("畸形 Token 续期返回 null")
    void renewGarbageReturnsNull() {
        assertThat(tokenService.renewToken("garbage")).isNull();
    }

    @Test
    @DisplayName("removeToken 删除签发时写入的同一 Redis key")
    void removeTokenDeletesKey() {
        ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();
        repositoryBackedByMap(store);
        String token = tokenService.generateToken(5L, "erin");
        assertThat(tokenService.validateToken(token)).isTrue();

        tokenService.removeToken(token);

        assertThat(store).isEmpty();
        assertThat(tokenService.validateToken(token)).isFalse();
    }
}
