package cn.chyuan.ai.domain.auth.service;

/**
 * Token 服务接口 — 生成和验证 JWT Token
 */
public interface ITokenService {

    /** 生成 Token */
    String generateToken(Long userId, String username);

    /** 生成带角色的 Token */
    String generateToken(Long userId, String username, String role);

    /** 从 Token 获取用户 ID */
    Long getUserIdFromToken(String token);

    /** 验证 Token 是否有效 */
    boolean validateToken(String token);

    /** 主动吊销 Token */
    void removeToken(String token);

    /**
     * 滑动续期 — 复用原 Token 的 jti 重新签发 JWT（新 exp = now + expiration），
     * 并重置 Redis TTL。用于每次请求刷新会话，实现"无操作 N 分钟后失效"的滑动过期。
     * 返回新 Token；原 Token 解析失败返回 null。
     */
    String renewToken(String token);
}
