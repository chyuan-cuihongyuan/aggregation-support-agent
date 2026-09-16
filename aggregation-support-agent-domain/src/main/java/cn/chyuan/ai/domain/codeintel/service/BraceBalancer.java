package cn.chyuan.ai.domain.codeintel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 括号配平与语法完整性（工单 0432 AZ6，aider 语法完整性检查思想）。
 * 花括号/圆括号/方括号栈配平 + 字符串（单双引号与转义）与行/块注释内豁免 +
 * 不平衡类型与行号报告（多余闭括号/缺失闭括号）。纯函数。
 */
public class BraceBalancer {

    /** 缺陷 */
    public record Defect(int line, String type, String detail) {
    }

    /** 配平结果 */
    public record Balance(boolean balanced, List<Defect> defects) {
    }

    private record Frame(char open, int line) {
    }

    public Balance balance(String source) {
        List<Defect> defects = new ArrayList<>();
        List<Frame> stack = new ArrayList<>();
        boolean inString = false;
        char stringQuote = 0;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        int line = 1;
        if (source == null) {
            return new Balance(true, defects);
        }
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\n') {
                line++;
                inLineComment = false;
                continue;
            }
            if (inLineComment) {
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == stringQuote) {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '/' -> {
                    if (i + 1 < source.length() && source.charAt(i + 1) == '/') {
                        inLineComment = true;
                        i++;
                    } else if (i + 1 < source.length() && source.charAt(i + 1) == '*') {
                        inBlockComment = true;
                        i++;
                    }
                }
                case '"', '\'' -> {
                    inString = true;
                    stringQuote = c;
                }
                case '{', '(', '[' -> stack.add(new Frame(c, line));
                case '}', ')', ']' -> {
                    char expected = switch (c) {
                        case '}' -> '{';
                        case ')' -> '(';
                        default -> '[';
                    };
                    if (stack.isEmpty() || stack.get(stack.size() - 1).open() != expected) {
                        defects.add(new Defect(line, "多余闭括号", "意外的 '" + c + "'"));
                    } else {
                        stack.remove(stack.size() - 1);
                    }
                }
                default -> {
                    // 其它字符忽略
                }
            }
        }
        for (Frame frame : stack) {
            defects.add(new Defect(frame.line(), "缺失闭括号", "'" + frame.open() + "' 未闭合"));
        }
        return new Balance(defects.isEmpty(), defects);
    }
}
