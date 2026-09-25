package cn.chyuan.ai.domain.grepkernel.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 内容检索端口（工单 0861 CW8，ripgrep 思想）。
 * search 入口统一编排/与 searchkernel 候选作检索来源形态只读联动（泛型条目不 import）/
 * grep-kernel.enabled 默认关（开启才改变行为）。
 */
public interface GrepPort {

    /** 内存文件集检索 */
    GrepEngine.Result search(Map<String, List<String>> files, GrepQuery query, GrepEngine.Options options);

    /** searchkernel 只读联动形态：任意条目形状按 title·body 文本行检索（形状数据不 import searchkernel） */
    <T> List<T> searchOver(List<T> items, Function<T, List<String>> linesOf, Function<T, T> selfOf,
                           GrepQuery query);

    static GrepPort inMemory() {
        return new InMemoryGrep();
    }
}

final class InMemoryGrep implements GrepPort {

    @Override
    public GrepEngine.Result search(Map<String, List<String>> files, GrepQuery query, GrepEngine.Options options) {
        return new GrepEngine(query, options).search(files);
    }

    @Override
    public <T> List<T> searchOver(List<T> items, Function<T, List<String>> linesOf,
                                  Function<T, T> selfOf, GrepQuery query) {
        List<T> out = new java.util.ArrayList<>();
        for (T item : items) {
            for (String line : linesOf.apply(item)) {
                if (query.matches(line)) {
                    out.add(selfOf.apply(item));
                    break;
                }
            }
        }
        return out;
    }
}
