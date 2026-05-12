package cn.chyuan.ai.domain.auth.adapter.port;

public interface IPasswordEncoder {
    String encode(String rawPassword);
    boolean matches(String rawPassword, String encodedPassword);
}
