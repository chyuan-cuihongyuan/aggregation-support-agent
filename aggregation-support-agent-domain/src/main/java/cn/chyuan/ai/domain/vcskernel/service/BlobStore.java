package cn.chyuan.ai.domain.vcskernel.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * blob 内容寻址（工单 0610 BU1，git blob 思想）。
 * 字节内容+类型前缀 SHA-256 摘要（JDK MessageDigest，十六进制 oid）/
 * 内容寻址去重（同内容同 oid 单实例）/对象仓库存取/缺失 oid 拒绝。
 */
public final class BlobStore {

    /** oid 前缀 */
    public static final String TYPE = "blob";

    private final Map<String, byte[]> objects = new LinkedHashMap<>();

    /** 内容摘要 oid（blob:sha256hex；内容寻址，与存储无关） */
    public static String oid(byte[] content) {
        if (content == null) {
            throw new IllegalArgumentException("内容不得为 null");
        }
        return TYPE + ":" + hex(digest((TYPE + "\n").getBytes(StandardCharsets.UTF_8), content));
    }

    /** 存入（同内容去重），返回 oid */
    public synchronized String put(byte[] content) {
        String oid = oid(content);
        objects.putIfAbsent(oid, content);
        return oid;
    }

    /** 读取（缺失拒绝） */
    public synchronized byte[] get(String oid) {
        byte[] content = objects.get(oid);
        if (content == null) {
            throw new IllegalArgumentException("对象缺失：" + oid);
        }
        return content;
    }

    /** 是否已存 */
    public synchronized boolean contains(String oid) {
        return objects.containsKey(oid);
    }

    /** 对象数 */
    public synchronized int size() {
        return objects.size();
    }

    static byte[] digest(byte[] prefix, byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(prefix);
            return md.digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺 SHA-256", e);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
