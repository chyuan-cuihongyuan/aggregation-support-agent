package cn.chyuan.ai.domain.modelkernel.service;

/**
 * digest 校验（工单 1030 EL2，ollama 内容寻址思想）。
 * sha256:hex 格式/非法字符拒绝/长度不符拒绝。
 */
public final class Digests {

    public static final String PREFIX = "sha256:";
    private static final int HEX_LEN = 64;

    private Digests() {
    }

    /** sha256:<64 位小写十六进制>；缺前缀/长度不符/非法字符/空拒绝 */
    public static void validate(String digest) {
        if (digest == null || digest.isEmpty()) {
            throw new IllegalArgumentException("digest 为空");
        }
        if (!digest.startsWith(PREFIX)) {
            throw new IllegalArgumentException("digest 缺 sha256: 前缀: " + digest);
        }
        String hex = digest.substring(PREFIX.length());
        if (hex.length() != HEX_LEN) {
            throw new IllegalArgumentException("digest 长度不符: " + hex.length());
        }
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!ok) {
                throw new IllegalArgumentException("digest 非法字符 '" + c + "': " + digest);
            }
        }
    }

    public static boolean isValid(String digest) {
        try {
            validate(digest);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
