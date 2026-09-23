package cn.chyuan.ai.domain.compresskernel.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 预设词典（工单 0589 BR4，zstd dictionary 思想）。
 * 种子字节前置为虚拟历史窗口（匹配可指向词典内）/
 * 词典指纹（SHA-256 截断 8 字节）参与帧头校验/
 * 重复样本内容消除对比/空词典与超限词典拒绝。
 */
public final class CompressionDictionary {

    /** 词典长度上限（与最大窗口一致，超出即不可达） */
    public static final int MAX_DICT_BYTES = 32 * 1024;

    private static final long NO_DICT_FINGERPRINT = 0L;

    private CompressionDictionary() {
    }

    /** 词典指纹：SHA-256 前 8 字节大端 long；空词典返回 0 */
    public static long fingerprint(byte[] dict) {
        if (dict == null || dict.length == 0) {
            return NO_DICT_FINGERPRINT;
        }
        validate(dict);
        byte[] digest = sha256(dict);
        long fp = 0L;
        for (int i = 0; i < 8; i++) {
            fp = (fp << 8) | (digest[i] & 0xFFL);
        }
        return fp;
    }

    /** 词典校验：非 null、不超上限 */
    public static void validate(byte[] dict) {
        if (dict == null) {
            throw new IllegalArgumentException("词典不得为 null");
        }
        if (dict.length > MAX_DICT_BYTES) {
            throw new IllegalArgumentException("词典超限：" + dict.length + " > " + MAX_DICT_BYTES);
        }
    }

    /** 从文本种子构建词典（UTF-8 字节） */
    public static byte[] fromText(String seed) {
        if (seed == null) {
            throw new IllegalArgumentException("种子文本不得为 null");
        }
        byte[] dict = seed.getBytes(StandardCharsets.UTF_8);
        validate(dict);
        return dict;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺 SHA-256", e);
        }
    }
}
