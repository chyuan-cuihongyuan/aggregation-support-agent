package cn.chyuan.ai.domain.editorkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 缓冲区行模型（工单 0708 CF1，neovim 思想）。
 * 行数组按编码点读写/插入删除与行拼接拆分/行号 0 基/
 * 多行文本载入与序列化/\n 归一/非法索引拒绝。
 */
public final class Buffer {

    private final List<String> lines = new ArrayList<>();

    public Buffer() {
        lines.add("");
    }

    /** 载入：\r\n 归一为 \n；结尾 \n 不产生额外空行（vim 语义）；空文本为单空行 */
    public static Buffer load(String text) {
        Buffer buffer = new Buffer();
        String normalized = text == null ? "" : text.replace("\r\n", "\n").replace("\r", "\n");
        if (!normalized.isEmpty()) {
            String[] parts = normalized.split("\n", -1);
            int end = parts.length;
            if (end > 1 && parts[end - 1].isEmpty()) {
                end--;
            }
            buffer.lines.clear();
            for (int i = 0; i < end; i++) {
                buffer.lines.add(parts[i]);
            }
        }
        return buffer;
    }

    public int lineCount() {
        return lines.size();
    }

    public String line(int index) {
        checkLine(index);
        return lines.get(index);
    }

    public void setLine(int index, String content) {
        checkLine(index);
        lines.set(index, content == null ? "" : content);
    }

    /** 行内编码点读取（col 为编码点偏移，0 基） */
    public int codePointAt(int line, int col) {
        String s = line(line);
        checkCol(s, col);
        return s.codePointAt(offsetOf(s, col));
    }

    /** 行内编码点插入 */
    public void insertCodeAt(int line, int col, int codePoint) {
        String s = line(line);
        if (col < 0 || col > codePointCount(s)) {
            throw new IllegalArgumentException("非法列号: " + col);
        }
        int at = col == codePointCount(s) ? s.length() : offsetOf(s, col);
        lines.set(line, new StringBuilder(s).insert(at, Character.toChars(codePoint)).toString());
    }

    /** 行内编码点删除 */
    public void deleteCodeAt(int line, int col) {
        String s = line(line);
        checkCol(s, col);
        int at = offsetOf(s, col);
        int end = s.offsetByCodePoints(at, 1);
        lines.set(line, s.substring(0, at) + s.substring(end));
    }

    /** 插入新行（at 允许 == lineCount，即末尾追加） */
    public void insertLine(int at, String content) {
        if (at < 0 || at > lines.size()) {
            throw new IllegalArgumentException("非法行号: " + at);
        }
        lines.add(at, content == null ? "" : content);
    }

    public void removeLine(int at) {
        checkLine(at);
        lines.remove(at);
        if (lines.isEmpty()) {
            lines.add("");
        }
    }

    /** 当前行与下一行拼接 */
    public void joinWithNext(int line) {
        checkLine(line);
        if (line + 1 >= lines.size()) {
            throw new IllegalArgumentException("末行无可拼接行");
        }
        lines.set(line, lines.get(line) + lines.get(line + 1));
        lines.remove(line + 1);
    }

    /** 行内指定编码点列处拆分为两行 */
    public void splitLine(int line, int col) {
        String s = line(line);
        if (col < 0 || col > codePointCount(s)) {
            throw new IllegalArgumentException("非法列号: " + col);
        }
        int at = col == codePointCount(s) ? s.length() : offsetOf(s, col);
        String tail = s.substring(at);
        lines.set(line, s.substring(0, at));
        lines.add(line + 1, tail);
    }

    /** 序列化：行间 \n，非空缓冲以 \n 结尾（vim 文件语义） */
    public String serialize() {
        if (lines.size() == 1 && lines.get(0).isEmpty()) {
            return "";
        }
        return String.join("\n", lines) + "\n";
    }

    public List<String> snapshotLines() {
        return List.copyOf(lines);
    }

    private static void checkCol(String s, int col) {
        if (col < 0 || col >= codePointCount(s)) {
            throw new IllegalArgumentException("非法列号: " + col);
        }
    }

    private void checkLine(int index) {
        if (index < 0 || index >= lines.size()) {
            throw new IllegalArgumentException("非法行号: " + index);
        }
    }

    private static int codePointCount(String s) {
        return s.codePointCount(0, s.length());
    }

    private static int offsetOf(String s, int col) {
        return s.offsetByCodePoints(0, col);
    }
}
