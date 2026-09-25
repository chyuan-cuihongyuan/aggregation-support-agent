package cn.chyuan.ai.domain.grepkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.PatternSyntaxException;

/**
 * 检索模式（工单 0854-0855 CW1·CW2，ripgrep 思想）。
 * 字面量子串大小写智能/正则子集/多模式任一命中并记录命中模式序号。
 */
public final class GrepQuery {

    public sealed interface Pattern permits Literal, Regex {
        /** 命中返回起始列（0 基），未命中返回 -1 */
        int find(String line);
    }

    /** 字面量：全小写模式自动大小写不敏感（smart case） */
    public record Literal(String text, boolean caseSensitive) implements Pattern {
        public Literal {
            if (text == null || text.isEmpty()) {
                throw new IllegalArgumentException("字面量模式为空");
            }
        }

        public static Literal smart(String text) {
            return new Literal(text, text.chars().anyMatch(Character::isUpperCase));
        }

        @Override
        public int find(String line) {
            String hay = caseSensitive ? line : line.toLowerCase();
            String needle = caseSensitive ? text : text.toLowerCase();
            return hay.indexOf(needle);
        }
    }

    /** 正则子集（java regex 语义） */
    public static final class Regex implements Pattern {
        private final String source;
        private final java.util.regex.Pattern compiled;

        public Regex(String source) {
            try {
                this.compiled = java.util.regex.Pattern.compile(source);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("非法正则: " + source, e);
            }
            this.source = source;
        }

        public String source() {
            return source;
        }

        @Override
        public int find(String line) {
            var m = compiled.matcher(line);
            return m.find() ? m.start() : -1;
        }
    }

    private final List<Pattern> patterns = new ArrayList<>();

    public GrepQuery add(Pattern pattern) {
        patterns.add(pattern);
        return this;
    }

    public int patternCount() {
        return patterns.size();
    }

    /** 任一模式命中即命中，返回命中的模式序号（-1 未命中） */
    public int matchIndex(String line) {
        for (int i = 0; i < patterns.size(); i++) {
            if (patterns.get(i).find(line) >= 0) {
                return i;
            }
        }
        return -1;
    }

    public int findColumn(int patternIndex, String line) {
        return patterns.get(patternIndex).find(line);
    }

    public boolean matches(String line) {
        return matchIndex(line) >= 0;
    }
}
