package cn.chyuan.ai.domain.auth.service;

import cn.chyuan.ai.domain.auth.adapter.repository.ITokenRepository;
import cn.chyuan.ai.domain.auth.model.valobj.TokenVO;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.UUID;

@Slf4j
@Service
public class TokenService implements ITokenService {

    @Value("${auth.jwt.secret}")
    private String secret;

    @Value("${auth.jwt.expiration}")
    private long expiration;

    private final ITokenRepository tokenRepository;

    public TokenService(ITokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    @Override
    public String generateToken(Long userId, String username) {
        return generateToken(userId, username, "user").getToken();
    }

    public TokenVO generateToken(Long userId, String username, String role) {
        String jti = UUID.randomUUID().toString().replace("-", "");
        Date now = new Date();
        Date expireDate = new Date(now.getTime() + expiration);

        String token = Jwts.builder()
                .setId(jti)
                .setSubject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role)
                .setIssuedAt(now)
                .setExpiration(expireDate)
                .signWith(SignatureAlgorithm.HS256, secret)
                .compact();

        String redisKey = buildRedisKey(userId, jti);
        tokenRepository.saveToken(redisKey, token, expiration / 1000);

        log.info("生成Token: userId={}, jti={}", userId, jti);
        return TokenVO.builder().token(token).expireAt(expireDate.getTime()).build();
    }

    @Override
    public Long getUserIdFromToken(String token) {
        Claims claims = parseToken(token);
        if (claims == null) return null;
        return Long.valueOf(claims.getSubject());
    }

    @Override
    public boolean validateToken(String token) {
        try {
            Claims claims = parseToken(token);
            if (claims == null) return false;

            String userId = claims.getSubject();
            String jti = claims.getId();
            String redisKey = buildRedisKey(Long.valueOf(userId), jti);

            return tokenRepository.queryToken(redisKey) != null;
        } catch (Exception e) {
            log.warn("Token校验失败: {}", e.getMessage());
            return false;
        }
    }

    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .setSigningKey(secret)
                    .parseClaimsJws(token)
                    .getBody();
        } catch (Exception e) {
            return null;
        }
    }

    public void removeToken(String token) {
        Claims claims = parseToken(token);
        if (claims != null) {
            String userId = claims.getSubject();
            String jti = claims.getId();
            tokenRepository.removeToken(buildRedisKey(Long.valueOf(userId), jti));
            log.info("移除Token: userId={}, jti={}", userId, jti);
        }
    }

    public TokenVO refreshToken(String oldToken) {
        Claims claims = parseToken(oldToken);
        if (claims == null) return null;

        Long userId = Long.valueOf(claims.getSubject());
        String username = claims.get("username", String.class);
        String role = claims.get("role", String.class);

        removeToken(oldToken);
        return generateToken(userId, username, role);
    }

    private String buildRedisKey(Long userId, String jti) {
        return "auth:token:" + userId + ":" + jti;
    }
}
