package cn.chyuan.ai.domain.docintel.service;

import cn.chyuan.ai.domain.docintel.model.valobj.LayoutBlockVO;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档指纹与版面 diff（工单 0392 AV6）。
 * 叶元素 → 规范化文本块指纹序列（SHA-256，去空白+全角折半+小写归一）；两版指纹 LCS diff →
 * ADDED/REMOVED/MOVED（指纹同现但位置改变）三类操作留痕；同文档 diff 为空。纯函数。
 */
public class DocumentFingerprinter {

    /** 规范化指纹 */
    public List<String> fingerprints(List<LayoutBlockVO> leaves) {
        List<String> out = new ArrayList<>();
        for (LayoutBlockVO block : leaves == null ? List.<LayoutBlockVO>of() : leaves) {
            out.add(sha256(normalize(block.getText())));
        }
        return out;
    }

    /** 单块指纹（测试观测） */
    public String fingerprint(String text) {
        return sha256(normalize(text));
    }

    /** 规范化：去全部空白 + 全角折半 + 小写 */
    String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (char ch : text.toCharArray()) {
            if (Character.isWhitespace(ch)) {
                continue;
            }
            if (ch >= 0xFF01 && ch <= 0xFF5E) {
                sb.append((char) (ch - 0xFEE0));
            } else if (ch == 0x3000) {
                sb.append(' ');
            } else {
                sb.append(Character.toLowerCase(ch));
            }
        }
        return sb.toString();
    }

    private String sha256(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 版面 diff */
    public static final class LayoutDiffer {

        /** 操作类型 */
        public static final String ADDED = "ADDED";
        public static final String REMOVED = "REMOVED";
        public static final String MOVED = "MOVED";

        /** diff 操作：类型 + 旧序号（REMOVED/MOVED）/新序号（ADDED/MOVED） */
        public record Op(String type, int oldIndex, int newIndex) {
        }

        /**
         * LCS diff：匹配对位置发生相对位移 → MOVED；旧未匹配 → REMOVED；新未匹配 → ADDED。
         */
        public List<Op> diff(List<String> oldSeq, List<String> newSeq) {
            int n = oldSeq == null ? 0 : oldSeq.size();
            int m = newSeq == null ? 0 : newSeq.size();
            int[][] dp = new int[n + 1][m + 1];
            for (int i = 1; i <= n; i++) {
                for (int j = 1; j <= m; j++) {
                    dp[i][j] = oldSeq.get(i - 1).equals(newSeq.get(j - 1))
                            ? dp[i - 1][j - 1] + 1
                            : Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
            boolean[] oldMatched = new boolean[n];
            boolean[] newMatched = new boolean[m];
            List<Op> matched = new ArrayList<>();
            int i = n;
            int j = m;
            while (i > 0 && j > 0) {
                if (oldSeq.get(i - 1).equals(newSeq.get(j - 1))) {
                    oldMatched[i - 1] = true;
                    newMatched[j - 1] = true;
                    matched.add(new Op(MOVED, i - 1, j - 1));
                    i--;
                    j--;
                } else if (dp[i - 1][j] >= dp[i][j - 1]) {
                    i--;
                } else {
                    j--;
                }
            }
            java.util.Collections.reverse(matched);
            List<Op> ops = new ArrayList<>();
            for (Op op : matched) {
                // 指纹同现但绝对位置改变 → MOVED；原位匹配不产出（同文档 diff 为空）
                if (op.oldIndex() != op.newIndex()) {
                    ops.add(new Op(MOVED, op.oldIndex(), op.newIndex()));
                }
            }
            for (int k = 0; k < n; k++) {
                if (!oldMatched[k]) {
                    ops.add(new Op(REMOVED, k, -1));
                }
            }
            for (int k = 0; k < m; k++) {
                if (!newMatched[k]) {
                    ops.add(new Op(ADDED, -1, k));
                }
            }
            return ops;
        }
    }
}
