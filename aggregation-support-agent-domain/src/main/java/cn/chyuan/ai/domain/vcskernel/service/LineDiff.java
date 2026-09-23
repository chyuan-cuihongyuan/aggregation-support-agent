package cn.chyuan.ai.domain.vcskernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 行级 diff（工单 0614 BU5，git diff 思想）。
 * LCS 最长公共子序列（行文本相等，O(nm) DP 带单元数上限熔断）/
 * 变更 hunk 分组（上下文行数可配）/统一 diff 文本（@@ 行号）/
 * 增删改行计数。
 */
public final class LineDiff {

    /** 变更算子 */
    public enum Op {
        EQUAL, DELETE, INSERT
    }

    /** diff 行 */
    public record Row(Op op, String text) {
    }

    /** hunk：@@ aStart bStart @@ + 行组 */
    public record Hunk(int aStart, int bStart, List<Row> rows) {
    }

    /** 统计 */
    public record Stat(int added, int deleted) {
    }

    /** DP 单元上限（熔断防大输入爆炸） */
    public static final int MAX_CELLS = 4_000_000;

    private LineDiff() {
    }

    /** LCS diff（先删后插确定性；超限熔断拒绝） */
    public static List<Row> diff(List<String> a, List<String> b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("两侧不得为 null");
        }
        long cells = (long) (a.size() + 1) * (b.size() + 1);
        if (cells > MAX_CELLS) {
            throw new IllegalArgumentException("diff 规模超限：" + a.size() + "x" + b.size());
        }
        int[][] lcs = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<Row> rows = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            if (a.get(i).equals(b.get(j))) {
                rows.add(new Row(Op.EQUAL, a.get(i)));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                rows.add(new Row(Op.DELETE, a.get(i)));
                i++;
            } else {
                rows.add(new Row(Op.INSERT, b.get(j)));
                j++;
            }
        }
        while (i < a.size()) {
            rows.add(new Row(Op.DELETE, a.get(i++)));
        }
        while (j < b.size()) {
            rows.add(new Row(Op.INSERT, b.get(j++)));
        }
        return rows;
    }

    /** hunk 分组（上下文行数可配；相邻变更组间隔 ≤ 2*context 合并；无变更返回空） */
    public static List<Hunk> hunks(List<Row> rows, int context) {
        if (context < 0) {
            throw new IllegalArgumentException("上下文不得为负");
        }
        List<Hunk> out = new ArrayList<>();
        List<Integer> changeIndices = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).op() != Op.EQUAL) {
                changeIndices.add(i);
            }
        }
        if (changeIndices.isEmpty()) {
            return out;
        }
        int groupStart = 0;
        for (int c = 0; c <= changeIndices.size(); c++) {
            boolean groupEnd = c == changeIndices.size()
                    || (c > 0 && c < changeIndices.size()
                    && changeIndices.get(c) - changeIndices.get(c - 1) > 2 * context + 1);
            if (!groupEnd) {
                continue;
            }
            int first = changeIndices.get(groupStart);
            int last = changeIndices.get(c - 1);
            int from = Math.max(0, first - context);
            int to = Math.min(rows.size(), last + context + 1);
            int aStart = 1;
            int bStart = 1;
            for (int k = 0; k < from; k++) {
                Op op = rows.get(k).op();
                if (op != Op.INSERT) {
                    aStart++;
                }
                if (op != Op.DELETE) {
                    bStart++;
                }
            }
            List<Row> hunkRows = new ArrayList<>(rows.subList(from, to));
            out.add(new Hunk(aStart, bStart, hunkRows));
            groupStart = c;
        }
        return out;
    }

    /** 统一 diff 文本（@@ 头 + ' '/'-'/'+ 行） */
    public static String unified(List<String> a, List<String> b, int context) {
        List<Hunk> hunks = hunks(diff(a, b), context);
        if (hunks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Hunk hunk : hunks) {
            int aCount = 0;
            int bCount = 0;
            for (Row row : hunk.rows()) {
                if (row.op() == Op.DELETE) {
                    aCount++;
                } else if (row.op() == Op.INSERT) {
                    bCount++;
                } else {
                    aCount++;
                    bCount++;
                }
            }
            sb.append("@@ -").append(hunk.aStart()).append(',').append(aCount)
                    .append(" +").append(hunk.bStart()).append(',').append(bCount)
                    .append(" @@\n");
            for (Row row : hunk.rows()) {
                sb.append(switch (row.op()) {
                    case EQUAL -> ' ';
                    case DELETE -> '-';
                    case INSERT -> '+';
                }).append(row.text()).append('\n');
            }
        }
        return sb.toString();
    }

    /** 增删统计 */
    public static Stat stat(List<Row> rows) {
        int added = 0;
        int deleted = 0;
        for (Row row : rows) {
            if (row.op() == Op.INSERT) {
                added++;
            } else if (row.op() == Op.DELETE) {
                deleted++;
            }
        }
        return new Stat(added, deleted);
    }
}
