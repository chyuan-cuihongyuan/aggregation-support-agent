package cn.chyuan.ai.domain.tokenkernel.service;

/**
 * 归一化链（工单 0740 CJ1，transformers 思想）。
 * 小写/空白折叠/全角半角 NFKC 子集/链式组合与顺序确定性。
 */
public final class Normalizer {

    private final boolean lowercase;
    private final boolean collapseWhitespace;
    private final boolean fullwidth;

    public Normalizer(boolean lowercase, boolean collapseWhitespace, boolean fullwidth) {
        this.lowercase = lowercase;
        this.collapseWhitespace = collapseWhitespace;
        this.fullwidth = fullwidth;
    }

    public static Normalizer off() {
        return new Normalizer(false, false, false);
    }

    /** 按固定顺序归一：全角半角 → 小写 → 空白折叠（顺序确定性） */
    public String normalize(String text) {
        if (text == null) {
            throw new IllegalArgumentException("输入不可为 null");
        }
        String out = text;
        if (fullwidth) {
            out = toHalfwidth(out);
        }
        if (lowercase) {
            out = out.toLowerCase(java.util.Locale.ROOT);
        }
        if (collapseWhitespace) {
            out = out.replaceAll("\\s+", " ").trim();
        }
        return out;
    }

    /** 全角 ASCII 与常见空格 → 半角（NFKC 子集） */
    static String toHalfwidth(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (cp == 0x3000) {
                sb.append(' ');
            } else if (cp >= 0xFF01 && cp <= 0xFF5E) {
                sb.append((char) (cp - 0xFEE0));
            } else {
                sb.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    public boolean isLowercase() {
        return lowercase;
    }
}
