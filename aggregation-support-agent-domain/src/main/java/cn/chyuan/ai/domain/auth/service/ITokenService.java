package cn.chyuan.ai.domain.auth.service;

/**
 * Token 服务接口 — 生成和验证 JWT Token
 */
public interface ITokenService {

    /** 生成 Token */
    String generateToken(Long userId, String username);

    /** 从 Token 获取用户 ID */
    Long getUserIdFromToken(String token);

    /** 验证 Token 是否有效 */
    boolean validateToken(String token);
}
