package cn.chyuan.ai.domain.fuzzkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 多模式与高亮（工单 0773/0774 CM5·CM6，fzf 思想）。
 * 空格分隔 AND 依次过滤/前缀 ! 否定模式/反斜杠转义空白/匹配区间合并高亮。
 */
public final class FuzzQuery {

    /** 解析后的模式项：negated=否定，text=模式文本 */
    public record Term(boolean negated, String text) {
    }

    private final FuzzMatcher matcher;

    public FuzzQuery() {
        this(new FuzzMatcher());
    }

    public FuzzQuery(FuzzMatcher matcher) {
        this.matcher = matcher;
    }

    /** 解析：空格分隔；\ 转义下一字符；项首 ! 为否定（CM5） */
    public static List<Term> parse(String query) {
        List<Term> terms = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean negated = false;
        boolean started = false;
        int i = 0;
        while (i < query.length()) {
            char c = query.charAt(i);
            if (c == '\\' && i + 1 < query.length()) {
                current.append(query.charAt(i + 1));
                started = true;
                i += 2;
                continue;
            }
            if (c == ' ') {
                if (started) {
                    terms.add(new Term(negated, current.toString()));
                }
                current.setLength(0);
                negated = false;
                started = false;
                i++;
                continue;
            }
            if (!started && c == '!' && current.length() == 0) {
                negated = true;
                started = true;
                i++;
                continue;
            }
            current.append(c);
            started = true;
            i++;
        }
        if (started) {
            terms.add(new Term(negated, current.toString()));
        }
        return terms;
    }

    /** AND 语义：所有正向模式命中且所有否定模式不命中（CM5）；空查询全通过 */
    public boolean matches(String query, String text) {
        List<Term> terms = parse(query);
        for (Term term : terms) {
            Optional<FuzzMatcher.Result> r = matcher.match(term.text(), text);
            if (term.negated() == r.isPresent()) {
                return false;
            }
        }
        return true;
    }

    /** 过滤：保留全部通过者（保序） */
    public List<String> filter(String query, List<String> texts) {
        List<String> out = new ArrayList<>();
        for (String text : texts) {
            if (matches(query, text)) {
                out.add(text);
            }
        }
        return out;
    }

    /** 搜索：过滤 + 按正向模式合计分排序 */
    public List<FuzzMatcher.Candidate> search(String query, List<String> texts) {
        List<Term> terms = parse(query);
        List<FuzzMatcher.Candidate> out = new ArrayList<>();
        for (String text : texts) {
            if (!matches(query, text)) {
                continue;
            }
            int total = 0;
            for (Term term : terms) {
                if (!term.negated()) {
                    total += matcher.match(term.text(), text).map(FuzzMatcher.Result::score).orElse(0);
                }
            }
            out.add(new FuzzMatcher.Candidate(text, total, total));
        }
        out.sort((a, b) -> {
            if (a.score() != b.score()) {
                return Integer.compare(b.score(), a.score());
            }
            return a.text().compareTo(b.text());
        });
        return out;
    }

    /** 高亮：命中位置合并为闭开区间（CM6）；无命中空表 */
    public List<FuzzMatcher.Range> highlight(String pattern, String text) {
        List<FuzzMatcher.Range> ranges = new ArrayList<>();
        Optional<FuzzMatcher.Result> r = matcher.match(pattern, text);
        if (r.isEmpty()) {
            return ranges;
        }
        int start = -1;
        int prev = -2;
        for (int pos : r.get().positions()) {
            if (pos < 0 || pos >= text.length()) {
                throw new IllegalArgumentException("命中位置越界: " + pos);
            }
            if (pos == prev + 1) {
                prev = pos;
                continue;
            }
            if (start >= 0) {
                ranges.add(new FuzzMatcher.Range(start, prev + 1));
            }
            start = pos;
            prev = pos;
        }
        if (start >= 0) {
            ranges.add(new FuzzMatcher.Range(start, prev + 1));
        }
        return ranges;
    }
}
