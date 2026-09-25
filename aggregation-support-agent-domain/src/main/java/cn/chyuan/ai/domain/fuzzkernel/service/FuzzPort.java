package cn.chyuan.ai.domain.fuzzkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 模糊匹配端口（工单 0776 CM8，fzf 思想）。
 * search 入口统一编排/与 searchkernel 只读联动（检索候选标题文本作模糊排序输入形态，泛型入参不 import）/
 * fuzz-kernel.enabled 默认关（开启才改变行为）。
 */
public interface FuzzPort {

    /** 检索：过滤 + 排序 */
    List<FuzzMatcher.Candidate> search(String query, List<String> candidates);

    /** searchkernel 只读联动形态：任意候选形状按标题文本模糊排序（形状数据不 import searchkernel） */
    <T> List<T> rankOver(List<T> items, Function<T, String> textOf, String query);

    /** 高亮区间 */
    List<FuzzMatcher.Range> highlight(String query, String text);

    /** 内存实现 */
    static FuzzPort inMemory() {
        return new InMemoryFuzz();
    }
}

final class InMemoryFuzz implements FuzzPort {

    private final FuzzQuery query = new FuzzQuery();

    @Override
    public List<FuzzMatcher.Candidate> search(String q, List<String> candidates) {
        return query.search(q, candidates);
    }

    @Override
    public <T> List<T> rankOver(List<T> items, Function<T, String> textOf, String q) {
        List<String> texts = new ArrayList<>();
        items.forEach(item -> texts.add(textOf.apply(item)));
        List<FuzzMatcher.Candidate> ranked = query.search(q, texts);
        List<T> out = new ArrayList<>();
        for (FuzzMatcher.Candidate c : ranked) {
            for (T item : items) {
                if (textOf.apply(item).equals(c.text()) && !out.contains(item)) {
                    out.add(item);
                    break;
                }
            }
        }
        return out;
    }

    @Override
    public List<FuzzMatcher.Range> highlight(String q, String text) {
        return query.highlight(q, text);
    }
}
