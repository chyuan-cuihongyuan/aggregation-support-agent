package cn.chyuan.ai.domain.editorkernel.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ex 命令（工单 0712 CF5，neovim 思想）。
 * :w 序列化写/:q 脏检查拒绝/地址范围（行号 . $ % + -）解析/
 * :s 替换（正则子集与 g 计数返回）。
 */
public final class ExCommands {

    /** 地址范围：[from,to] 闭区间，1 基语义对外、0 基对内 */
    public record LineRange(int from, int to) {
    }

    /** 替换结果：影响行范围与替换次数 */
    public record SubResult(int from, int to, int count) {
    }

    private final Buffer buffer;
    private boolean dirty;
    private int cursorLine;

    public ExCommands(Buffer buffer) {
        this.buffer = buffer;
    }

    public boolean dirty() {
        return dirty;
    }

    public void touch() {
        dirty = true;
    }

    public void setCursorLine(int line) {
        buffer.line(line);
        cursorLine = line;
    }

    public int cursorLine() {
        return cursorLine;
    }

    /** 地址范围解析：空→当前行；. 当前；$ 末行；% 全文；数字；.+/-$ 端点后缀 */
    public LineRange parseRange(String spec) {
        int last = buffer.lineCount() - 1;
        String s = spec == null ? "" : spec.trim();
        if (s.isEmpty()) {
            return new LineRange(cursorLine, cursorLine);
        }
        if (s.equals("%")) {
            return new LineRange(0, last);
        }
        String[] parts = s.split(",", -1);
        if (parts.length > 2) {
            throw new IllegalArgumentException("非法范围: " + spec);
        }
        if (parts.length == 1) {
            int at = parseAddress(parts[0]);
            return new LineRange(at, at);
        }
        int from = parseAddress(parts[0]);
        int to = parseAddress(parts[1]);
        if (from > to) {
            throw new IllegalArgumentException("范围倒置: " + spec);
        }
        return new LineRange(from, to);
    }

    private int parseAddress(String spec) {
        String s = spec == null ? "" : spec.trim();
        if (s.isEmpty()) {
            return cursorLine;
        }
        int base;
        int i = 0;
        if (s.charAt(0) == '.') {
            base = cursorLine;
            i = 1;
        } else if (s.charAt(0) == '$') {
            base = buffer.lineCount() - 1;
            i = 1;
        } else if (s.charAt(0) == '+' || s.charAt(0) == '-') {
            base = cursorLine;
        } else if (Character.isDigit(s.charAt(0))) {
            int j = 0;
            while (j < s.length() && Character.isDigit(s.charAt(j))) {
                j++;
            }
            base = Integer.parseInt(s.substring(0, j)) - 1;
            i = j;
        } else {
            throw new IllegalArgumentException("非法地址: " + spec);
        }
        while (i < s.length()) {
            char op = s.charAt(i);
            if (op != '+' && op != '-') {
                throw new IllegalArgumentException("非法地址: " + spec);
            }
            i++;
            int num = 1;
            int j = i;
            while (j < s.length() && Character.isDigit(s.charAt(j))) {
                j++;
            }
            if (j > i) {
                num = Integer.parseInt(s.substring(i, j));
            }
            base += op == '+' ? num : -num;
            i = j;
        }
        if (base < 0 || base > buffer.lineCount() - 1) {
            throw new IllegalArgumentException("地址越界: " + spec);
        }
        return base;
    }

    /** :w：序列化并清脏标志，返回写出文本 */
    public String write() {
        dirty = false;
        return buffer.serialize();
    }

    /** :q：脏缓冲拒绝（E37） */
    public void quit() {
        if (dirty) {
            throw new IllegalStateException("E37: 已修改未保存");
        }
    }

    /** :{range}s/old/new/[g]：行内首处替换（global 时全处），返回替换次数 */
    public SubResult substitute(String rangeSpec, String regex, String replacement, boolean global) {
        LineRange range = parseRange(rangeSpec);
        if (regex == null || regex.isEmpty()) {
            throw new IllegalArgumentException("空模式");
        }
        Pattern pattern = Pattern.compile(regex);
        int count = 0;
        for (int i = range.from(); i <= range.to(); i++) {
            String line = buffer.line(i);
            Matcher matcher = pattern.matcher(line);
            StringBuilder rebuilt = new StringBuilder();
            int copied = 0;
            int lineHits = 0;
            while (matcher.find()) {
                lineHits++;
                rebuilt.append(line, copied, matcher.start()).append(replacement);
                copied = matcher.end();
                if (!global) {
                    break;
                }
            }
            if (lineHits > 0) {
                rebuilt.append(line, copied, line.length());
                buffer.setLine(i, rebuilt.toString());
                count += lineHits;
                dirty = true;
            }
        }
        if (count == 0) {
            throw new IllegalStateException("E486: 未找到模式: " + regex);
        }
        return new SubResult(range.from(), range.to(), count);
    }
}
