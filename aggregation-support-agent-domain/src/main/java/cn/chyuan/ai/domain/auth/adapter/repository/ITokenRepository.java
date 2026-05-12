package cn.chyuan.ai.domain.auth.adapter.repository;

public interface ITokenRepository {
    void saveToken(String key, String token, long expireSeconds);
    String queryToken(String key);
    void removeToken(String key);
}
