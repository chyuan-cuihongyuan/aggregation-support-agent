package cn.chyuan.ai.domain.migratekernel.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * checksum 指纹（工单 1153 EZ3，prisma 思想）。
 * 内容 sha256 指纹登记于应用时；已应用内容重算不一致即篡改拒绝；
 * 重算一致通过；内容变更需登记新条目而非改旧条。
 */
public final class Checksums {

    private final Map<String, String> fingerprints = new LinkedHashMap<>();

    /** sha256 十六进制指纹（JDK 内建，零新依赖） */
    public static String fingerprint(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺 SHA-256", e);
        }
    }

    /** 应用时绑定指纹；同条重绑需内容一致（幂等） */
    public void bind(String name, String content) {
        String fp = fingerprint(content);
        String existing = fingerprints.get(name);
        if (existing != null && !existing.equals(fp)) {
            throw new IllegalStateException("已应用内容篡改: " + name);
        }
        fingerprints.put(name, fp);
    }

    /** 校验已应用条内容：指纹不一致判篡改 */
    public void verify(String name, String content) {
        String expected = fingerprints.get(name);
        if (expected == null) {
            throw new IllegalArgumentException("未绑定指纹: " + name);
        }
        if (!expected.equals(fingerprint(content))) {
            throw new IllegalStateException("已应用内容篡改: " + name);
        }
    }

    public boolean bound(String name) {
        return fingerprints.containsKey(name);
    }

    public String of(String name) {
        return fingerprints.get(name);
    }
}
