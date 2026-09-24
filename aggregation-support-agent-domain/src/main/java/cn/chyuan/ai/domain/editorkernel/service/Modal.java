package cn.chyuan.ai.domain.editorkernel.service;

import java.util.Locale;

/**
 * 模态动作（工单 0711 CF4，neovim 思想）。
 * normal/insert/visual 模态机/operator+motion+count 组合/
 * word 行首行尾与绝对运动/visual 选区扩展操作/非法组合拒绝。
 */
public final class Modal {

    public enum Mode {NORMAL, INSERT, VISUAL}

    /** 光标位置：行 0 基、列编码点 0 基 */
    public record Cursor(int line, int col) {
    }

    /** 选区：[start,end) 归一化 */
    public record Range(int startLine, int startCol, int endLine, int endCol) {
    }

    /** 动作结果：受影响区间/摘取文本/行级标志 */
    public record Result(Range range, String yanked, boolean lineWise) {
    }

    public static final char[] OPERATORS = {'d', 'c', 'y'};
    /** 行级 motion（含 dd 特例同名判定）：结果按整行取 */
    private static final String LINEWISE_MOTIONS = "jk";

    private final Buffer buffer;
    private Mode mode = Mode.NORMAL;
    private int line;
    private int col;
    private int anchorLine;
    private int anchorCol;
    private boolean visualLineWise;

    public Modal(Buffer buffer) {
        this.buffer = buffer;
        clampCursor();
    }

    public Mode mode() {
        return mode;
    }

    public Cursor cursor() {
        return new Cursor(line, col);
    }

    public void enterInsert() {
        mode = Mode.INSERT;
    }

    public void enterVisual(boolean lineWise) {
        mode = Mode.VISUAL;
        visualLineWise = lineWise;
        anchorLine = line;
        anchorCol = col;
    }

    public void escape() {
        mode = Mode.NORMAL;
        clampCursor();
    }

    /** 纯 motion 移动光标 */
    public Result move(String motion, int count) {
        Cursor target = target(motion, count);
        line = target.line();
        col = target.col();
        clampCursor();
        return new Result(null, "", false);
    }

    /** INSERT 模式插入编码点 */
    public void insertCodePoint(int codePoint) {
        ensureInsert();
        buffer.insertCodeAt(line, col, codePoint);
        col++;
    }

    /** INSERT 模式换行 */
    public void insertNewline() {
        ensureInsert();
        buffer.splitLine(line, col);
        line++;
        col = 0;
    }

    /** INSERT 模式退格 */
    public void backspace() {
        ensureInsert();
        if (col > 0) {
            buffer.deleteCodeAt(line, col - 1);
            col--;
        } else if (line > 0) {
            int prevLen = codePointLen(buffer.line(line - 1));
            buffer.joinWithNext(line - 1);
            line--;
            col = prevLen;
        }
    }

    /** operator+motion+count 组合：d/c/y；同名双写（dd/cc/yy）行级特例 */
    public Result operate(char operator, String motion, int count) {
        checkOperator(operator);
        if (mode == Mode.INSERT) {
            throw new IllegalStateException("INSERT 模式不支持 operator");
        }
        Range range;
        boolean lineWise;
        if (motion.length() == 1 && motion.charAt(0) == operator) {
            range = new Range(line, 0, Math.min(buffer.lineCount(), line + count), 0);
            lineWise = true;
        } else if (mode == Mode.VISUAL) {
            if (motion != null && !motion.isEmpty()) {
                Cursor target = target(motion, count);
                line = target.line();
                col = target.col();
                clampCursor();
            }
            range = normalize(anchorLine, anchorCol, line, col);
            lineWise = visualLineWise;
        } else {
            Cursor target = target(motion, count);
            range = normalize(line, col, target.line(), target.col());
            lineWise = LINEWISE_MOTIONS.indexOf(motion.charAt(0)) >= 0 || motion.equals("gg");
        }
        String yanked = applyRange(range, operator, lineWise);
        if (mode == Mode.VISUAL) {
            mode = Mode.NORMAL;
            clampCursor();
        }
        return new Result(range, yanked, lineWise);
    }

    /** visual 选区扩展：纯 motion 移动活动端点 */
    public void visualExtend(String motion, int count) {
        if (mode != Mode.VISUAL) {
            throw new IllegalStateException("非 VISUAL 模式");
        }
        Result ignore = move(motion, count);
    }

    // ---- 内部：motion 目标计算 ----

    private Cursor target(String motion, int count) {
        if (motion == null || motion.isEmpty()) {
            throw new IllegalArgumentException("缺少 motion");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("count 须 > 0");
        }
        if (motion.equals("gg")) {
            return new Cursor(0, 0);
        }
        if (motion.equals("G")) {
            int last = buffer.lineCount() - 1;
            return new Cursor(last, 0);
        }
        if (motion.length() != 1) {
            throw new IllegalArgumentException("非法 motion: " + motion);
        }
        char c = Character.toLowerCase(motion.charAt(0));
        if (c == 'g') {
            throw new IllegalArgumentException("非法 motion: " + motion);
        }
        return switch (c) {
            case 'h' -> new Cursor(line, Math.max(0, col - count));
            case 'l' -> new Cursor(line, Math.min(codePointLen(buffer.line(line)), col + count));
            case 'w' -> wordForward(count);
            case 'b' -> wordBackward(count);
            case 'e' -> wordEnd(count);
            case '0' -> new Cursor(line, 0);
            case '$' -> new Cursor(line, codePointLen(buffer.line(line)));
            case 'k' -> new Cursor(Math.max(0, line - count), 0);
            case 'j' -> new Cursor(Math.min(buffer.lineCount() - 1, line + count), 0);
            default -> throw new IllegalArgumentException("非法 motion: " + motion);
        };
    }

    private String applyRange(Range range, char operator, boolean lineWise) {
        String yanked;
        if (lineWise) {
            StringBuilder sb = new StringBuilder();
            for (int i = range.startLine(); i < range.endLine(); i++) {
                sb.append(buffer.line(i)).append('\n');
            }
            yanked = sb.toString();
        } else {
            yanked = slice(range);
        }
        switch (operator) {
            case 'y' -> {
                line = range.startLine();
                col = lineWise ? 0 : range.startCol();
                clampCursor();
            }
            case 'd', 'c' -> {
                if (lineWise) {
                    for (int i = range.endLine() - 1; i >= range.startLine(); i--) {
                        buffer.removeLine(i);
                    }
                    line = Math.min(range.startLine(), buffer.lineCount() - 1);
                    col = 0;
                } else {
                    deleteSlice(range);
                    line = range.startLine();
                    col = range.startCol();
                }
                clampCursor();
                if (operator == 'c') {
                    mode = Mode.INSERT;
                }
            }
            default -> throw new IllegalArgumentException("非法 operator: " + operator);
        }
        return yanked;
    }

    private Cursor wordForward(int count) {
        int l = line;
        int c = col;
        for (int i = 0; i < count; i++) {
            int len = codePointLen(buffer.line(l));
            if (len == 0) {
                if (l + 1 < buffer.lineCount()) {
                    l++;
                    c = 0;
                    continue;
                }
                break;
            }
            c = Math.min(len, c + 1);
            while (c < len && !isWord(buffer.line(l), c)) {
                c++;
            }
            while (c < len && isWord(buffer.line(l), c)) {
                c++;
            }
            while (c < len && !isWord(buffer.line(l), c)) {
                c++;
            }
            if (c >= len && l + 1 < buffer.lineCount()) {
                l++;
                c = 0;
            }
        }
        return new Cursor(l, c);
    }

    private Cursor wordBackward(int count) {
        int l = line;
        int c = col;
        for (int i = 0; i < count; i++) {
            if (c == 0 && l > 0) {
                l--;
                c = codePointLen(buffer.line(l));
            }
            c = Math.max(0, c - 1);
            while (c > 0 && !isWord(buffer.line(l), c)) {
                c--;
            }
            while (c > 0 && isWord(buffer.line(l), c - 1)) {
                c--;
            }
        }
        return new Cursor(l, c);
    }

    private Cursor wordEnd(int count) {
        int l = line;
        int c = col;
        for (int i = 0; i < count; i++) {
            int len = codePointLen(buffer.line(l));
            c = Math.min(len, c + 1);
            while (c < len && !isWord(buffer.line(l), c)) {
                c++;
            }
            while (c < len && isWord(buffer.line(l), c)) {
                c++;
            }
            if (c >= len && l + 1 < buffer.lineCount() && i + 1 < count) {
                l++;
                c = 0;
            }
        }
        return new Cursor(l, c);
    }

    private boolean isWord(String s, int cpIndex) {
        int at = cpIndex >= codePointLen(s) ? s.length() - 1 : s.offsetByCodePoints(0, cpIndex);
        return Character.isLetterOrDigit(s.charAt(Math.max(0, at)));
    }

    private void clampCursor() {
        line = Math.max(0, Math.min(line, buffer.lineCount() - 1));
        int len = codePointLen(buffer.line(line));
        if (mode == Mode.NORMAL && len > 0 && col >= len) {
            col = len - 1;
        } else {
            col = Math.max(0, Math.min(col, len));
        }
    }

    private void ensureInsert() {
        if (mode != Mode.INSERT) {
            throw new IllegalStateException("非 INSERT 模式");
        }
    }

    private static void checkOperator(char operator) {
        for (char op : OPERATORS) {
            if (op == operator) {
                return;
            }
        }
        throw new IllegalArgumentException("非法 operator: " + operator);
    }

    private static Range normalize(int l1, int c1, int l2, int c2) {
        if (l1 > l2 || (l1 == l2 && c1 > c2)) {
            return new Range(l2, c2, l1, c1);
        }
        return new Range(l1, c1, l2, c2);
    }

    static int codePointLen(String s) {
        return s.codePointCount(0, s.length());
    }

    static String substring(String s, int fromCp, int toCp) {
        int total = codePointLen(s);
        int from = Math.min(fromCp, total) >= total ? s.length() : s.offsetByCodePoints(0, Math.min(fromCp, total));
        int to = Math.min(toCp, total) >= total ? s.length() : s.offsetByCodePoints(0, Math.min(toCp, total));
        return s.substring(Math.min(from, to), Math.max(from, to));
    }

    private String slice(Range r) {
        if (r.startLine() == r.endLine()) {
            return substring(buffer.line(r.startLine()), r.startCol(), r.endCol());
        }
        StringBuilder sb = new StringBuilder();
        sb.append(substring(buffer.line(r.startLine()), r.startCol(), codePointLen(buffer.line(r.startLine()))));
        for (int i = r.startLine() + 1; i < r.endLine(); i++) {
            sb.append(buffer.line(i));
        }
        sb.append(substring(buffer.line(r.endLine()), 0, r.endCol()));
        return sb.toString();
    }

    private void deleteSlice(Range r) {
        String head = substring(buffer.line(r.startLine()), 0, r.startCol());
        String last = buffer.line(r.endLine());
        String tail = substring(last, r.endCol(), codePointLen(last));
        buffer.setLine(r.startLine(), head + tail);
        for (int i = r.endLine(); i > r.startLine(); i--) {
            buffer.removeLine(i);
        }
    }
}
