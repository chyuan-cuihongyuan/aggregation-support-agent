package cn.chyuan.ai.domain.editorkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 搜索（工单 0713 CF6，neovim 思想）。
 * 正则字面量与元字符子集/正向 n 与反向 N 循环回绕/匹配区间登记/无匹配拒绝。
 */
public final class SearchEngine {

    /** 匹配：行号与编码点列区间 [start,end) */
    public record Match(int line, int startCol, int endCol) {
    }

    /** 元字符子集白名单：字面量 + . * + ? [ ] ^ $ \ ( ) | { } 与空白 */
    private static final Pattern ALLOWED = Pattern.compile("[\\w \\t.\\[\\]^$\\\\*+?()|{}\\-]*");

    private final Buffer buffer;

    public SearchEngine(Buffer buffer) {
        this.buffer = buffer;
    }

    /** 模式校验：白名单子集 + 正则编译，非法报定位 */
    public Pattern compile(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            throw new IllegalArgumentException("空模式");
        }
        if (!ALLOWED.matcher(pattern).matches()) {
            int bad = 0;
            while (bad < pattern.length() && ALLOWED.matcher(pattern.substring(bad, bad + 1)).matches()) {
                bad++;
            }
            throw new IllegalArgumentException("模式含不支持元字符（位 " + bad + "）: " + pattern);
        }
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("模式编译失败（位 " + e.getIndex() + "）: " + pattern);
        }
    }

    /** 全量匹配登记（行序→列序） */
    public List<Match> allMatches(String pattern, int limit) {
        Pattern compiled = compile(pattern);
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 须 > 0");
        }
        List<Match> matches = new ArrayList<>();
        for (int i = 0; i < buffer.lineCount() && matches.size() < limit; i++) {
            Matcher matcher = compiled.matcher(buffer.line(i));
            while (matcher.find() && matches.size() < limit) {
                MatchResult r = matcher.toMatchResult();
                if (r.end() == r.start()) {
                    continue;
                }
                matches.add(new Match(i, buffer.line(i).codePointCount(0, r.start()),
                        buffer.line(i).codePointCount(0, r.end())));
            }
        }
        return matches;
    }

    /** 正向 n：从 (fromLine,fromCol) 起找首个匹配，末尾回绕（wrap） */
    public Match findNext(String pattern, int fromLine, int fromCol, boolean wrap) {
        Pattern compiled = compile(pattern);
        for (int i = fromLine; i < buffer.lineCount(); i++) {
            Match m = firstAtOrAfter(compiled, i, i == fromLine ? fromCol + 1 : 0);
            if (m != null) {
                return m;
            }
        }
        if (wrap) {
            for (int i = 0; i <= fromLine; i++) {
                Match m = firstAtOrAfter(compiled, i, 0);
                if (m != null) {
                    return m;
                }
            }
        }
        throw new IllegalStateException("E486: 未找到模式: " + pattern);
    }

    /** 反向 N：从 (fromLine,fromCol) 起向前找首个匹配，头部回绕 */
    public Match findPrev(String pattern, int fromLine, int fromCol, boolean wrap) {
        Pattern compiled = compile(pattern);
        for (int i = fromLine; i >= 0; i--) {
            Match m = lastBefore(compiled, i, i == fromLine ? fromCol : Integer.MAX_VALUE);
            if (m != null) {
                return m;
            }
        }
        if (wrap) {
            for (int i = buffer.lineCount() - 1; i >= fromLine; i--) {
                Match m = lastBefore(compiled, i, i == fromLine ? Integer.MAX_VALUE : Integer.MAX_VALUE);
                if (m != null) {
                    return m;
                }
            }
        }
        throw new IllegalStateException("E486: 未找到模式: " + pattern);
    }

    private Match firstAtOrAfter(Pattern compiled, int line, int minCol) {
        Matcher matcher = compiled.matcher(buffer.line(line));
        while (matcher.find()) {
            if (matcher.end() == matcher.start()) {
                continue;
            }
            int startCp = buffer.line(line).codePointCount(0, matcher.start());
            if (startCp >= minCol) {
                return new Match(line, startCp, buffer.line(line).codePointCount(0, matcher.end()));
            }
        }
        return null;
    }

    private Match lastBefore(Pattern compiled, int line, int beforeColInclusive) {
        Matcher matcher = compiled.matcher(buffer.line(line));
        Match last = null;
        while (matcher.find()) {
            if (matcher.end() == matcher.start()) {
                continue;
            }
            int startCp = buffer.line(line).codePointCount(0, matcher.start());
            if (startCp < beforeColInclusive) {
                last = new Match(line, startCp, buffer.line(line).codePointCount(0, matcher.end()));
            }
        }
        return last;
    }
}
