package cn.chyuan.ai.domain.codeintel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 代码切片（工单 0431 AZ5）。
 * 源码按行扫描函数声明起点（def/function/class/方法签名粗筛）→花括号配平切出函数体→
 * 目标行定位所属函数→切片输出（起止行号+前后文行数可配+行号前缀标注）。
 */
public class CodeSlicer {

    /** 切片结果 */
    public record Slice(int startLine, int endLine, List<String> lines) {
    }

    /** 切出包含目标行（1-based）的函数体；不在任何函数内返回 null */
    public Slice sliceContaining(String source, int targetLine) {
        List<String> lines = toLines(source);
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (isDeclaration(lines.get(i))) {
                int end = blockEnd(lines, i);
                if (end >= 0 && targetLine >= i + 1 && targetLine <= end + 1) {
                    start = i;
                    return new Slice(start + 1, end + 1, new ArrayList<>(lines.subList(start, end + 1)));
                }
                if (end >= 0) {
                    i = end;
                }
            }
        }
        return null;
    }

    /** 带前后文与行号前缀的切片文本 */
    public String renderWithWindow(String source, int targetLine, int contextLines) {
        Slice slice = sliceContaining(source, targetLine);
        if (slice == null) {
            return null;
        }
        List<String> all = toLines(source);
        int from = Math.max(0, slice.startLine() - 1 - contextLines);
        int to = Math.min(all.size(), slice.endLine() + contextLines);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            sb.append(i + 1).append(": ").append(all.get(i)).append('\n');
        }
        return sb.toString();
    }

    /** 声明行粗筛：java/js/python 常见关键字 + 花括号或冒号结尾倾向 */
    static boolean isDeclaration(String line) {
        String stripped = line.strip();
        return stripped.startsWith("def ") || stripped.startsWith("function ") || stripped.startsWith("class ")
                || stripped.matches("(public|private|protected|static).*\\(.*\\)\\s*\\{?\\s*")
                || stripped.matches("[A-Za-z_][A-Za-z0-9_<>]*\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\([^)]*\\)\\s*\\{\\s*");
    }

    /** 从声明行起花括号配平找块尾（返回行下标）；python 冒号缩进块按缩进回退 */
    private static int blockEnd(List<String> lines, int declIndex) {
        String decl = lines.get(declIndex);
        int depth = 0;
        boolean opened = false;
        for (int i = declIndex; i < lines.size(); i++) {
            for (char c : lines.get(i).toCharArray()) {
                if (c == '{') {
                    depth++;
                    opened = true;
                } else if (c == '}') {
                    depth--;
                }
            }
            if (opened && depth <= 0) {
                return i;
            }
            // python 冒号块：声明行以 : 结尾且未开括号 → 按下一行缩进判定
            if (!opened && i == declIndex && decl.strip().endsWith(":")) {
                int indentOfFirst = -1;
                for (int j = declIndex + 1; j < lines.size(); j++) {
                    if (lines.get(j).isBlank()) {
                        continue;
                    }
                    if (indentOfFirst < 0) {
                        indentOfFirst = leadingSpaces(lines.get(j));
                        if (indentOfFirst <= leadingSpaces(decl)) {
                            return j - 1;
                        }
                    } else if (leadingSpaces(lines.get(j)) < indentOfFirst) {
                        return j - 1;
                    }
                }
                return lines.size() - 1;
            }
        }
        return opened ? -1 : declIndex;
    }

    private static int leadingSpaces(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private static List<String> toLines(String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(List.of(text.split("\n", -1)));
    }
}
