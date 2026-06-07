package cn.chyuan.ai.trigger.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 认证 Cookie 配置。
 * dev 可关闭 Secure 以支持本地 HTTP，prod 应开启 Secure。
 */
@Component
@ConfigurationProperties(prefix = "auth.cookie")
public class AuthCookieProperties {

    private String name = "auth_token";
    private int maxAge = 604800;
    private String path = "/";
    private boolean httpOnly = true;
    private boolean secure = false;
    private String sameSite = "Lax";

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getMaxAge() {
        return maxAge;
    }

    public void setMaxAge(int maxAge) {
        this.maxAge = maxAge;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public boolean isHttpOnly() {
        return httpOnly;
    }

    public void setHttpOnly(boolean httpOnly) {
        this.httpOnly = httpOnly;
    }

    public boolean isSecure() {
        return secure;
    }

    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    public String getSameSite() {
        return sameSite;
    }

    public void setSameSite(String sameSite) {
        this.sameSite = sameSite;
    }
}
