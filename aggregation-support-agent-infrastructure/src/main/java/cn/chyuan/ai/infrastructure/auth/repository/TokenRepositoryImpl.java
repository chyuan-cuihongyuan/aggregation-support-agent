package cn.chyuan.ai.infrastructure.auth.repository;
import cn.chyuan.ai.domain.auth.adapter.repository.ITokenRepository;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;
import java.util.concurrent.TimeUnit;

@Repository
public class TokenRepositoryImpl implements ITokenRepository {

    private final RedisTemplate<String, Object> redisTemplate;

    public TokenRepositoryImpl(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void saveToken(String key, String token, long expireSeconds) {
        redisTemplate.opsForValue().set(key, token, expireSeconds, TimeUnit.SECONDS);
    }

    @Override
    public String queryToken(String key) {
        Object value = redisTemplate.opsForValue().get(key);
        return value != null ? value.toString() : null;
    }

    @Override
    public void removeToken(String key) {
        redisTemplate.delete(key);
    }
}
