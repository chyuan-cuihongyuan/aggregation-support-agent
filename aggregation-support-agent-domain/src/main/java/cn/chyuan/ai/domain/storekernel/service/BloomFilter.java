package cn.chyuan.ai.domain.storekernel.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.BitSet;

/**
 * bloom 过滤器（工单 0475 BE4，leveldb bloom 思想）。
 * 位数组 + k 哈希（FNV-1a 64 双重哈希派生）/误判率上界参数化
 * /查询段裁剪（假阳性允许、假阴性不允许）。
 */
public final class BloomFilter {

    private final BitSet bits;
    private final int hashCount;
    private final long[] seeds;

    private BloomFilter(BitSet bits, int hashCount, long[] seeds) {
        this.bits = bits;
        this.hashCount = hashCount;
        this.seeds = seeds;
    }

    /**
     * 按预期插入数 n 与误判率 p 计算参数构建：
     * m = -n·ln(p)/(ln2)²，k = round(m/n·ln2)。
     */
    public static BloomFilter create(long expectedInsertions, double falsePositiveRate) {
        if (expectedInsertions <= 0 || falsePositiveRate <= 0 || falsePositiveRate >= 1) {
            throw new IllegalArgumentException("n 须 > 0 且 0 < p < 1");
        }
        double ln2 = Math.log(2);
        int bits = (int) Math.ceil(-expectedInsertions * Math.log(falsePositiveRate) / (ln2 * ln2));
        int hashCount = Math.max(1, (int) Math.round((double) bits / expectedInsertions * ln2));
        long[] seeds = new long[hashCount];
        for (int i = 0; i < hashCount; i++) {
            seeds[i] = 0x9E3779B97F4A7C15L * (i + 1);
        }
        return new BloomFilter(new BitSet(Math.max(64, bits)), hashCount, seeds);
    }

    /** 写入键 */
    public void add(String key) {
        long h1 = fnv1a64(key);
        long h2 = h1 >>> 33 | h1 << 31;
        for (int i = 0; i < hashCount; i++) {
            bits.set(index(h1 + seeds[i] * h2, bits.size()));
        }
    }

    /** 查询键：false 表示必然不存在（可裁剪段），true 表示可能存在 */
    public boolean mightContain(String key) {
        long h1 = fnv1a64(key);
        long h2 = h1 >>> 33 | h1 << 31;
        for (int i = 0; i < hashCount; i++) {
            if (!bits.get(index(h1 + seeds[i] * h2, bits.size()))) {
                return false;
            }
        }
        return true;
    }

    private int index(long hash, int size) {
        long mixed = hash ^ (hash >>> 32);
        return (int) (Math.abs(mixed) % size);
    }

    /** FNV-1a 64 位 */
    static long fnv1a64(String content) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : content.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xFF);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /** 防御性 SHA-256 工具（校验场景复用，非 bloom 主路径） */
    static String sha256Hex16(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
