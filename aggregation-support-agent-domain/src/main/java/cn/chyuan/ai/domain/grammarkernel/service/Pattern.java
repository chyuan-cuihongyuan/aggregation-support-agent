package cn.chyuan.ai.domain.grammarkernel.service;

import java.util.List;

/**
 * 文法模式元素（工单 1222 FH3 / 1223 FH4 / 1224 FH5，antlr 思想）。
 * Ref 规则引用/Token 字面量/Seq 序列/Alt 选择/Empty ε/Many 基数（* + ?）；
 * first 集计算在 ParserEngine 内结合文法上下文递归解析。
 */
public sealed interface Pattern {

    record Ref(String name) implements Pattern {
    }

    record Token(String literal) implements Pattern {
    }

    record Seq(List<Pattern> parts) implements Pattern {
    }

    record Alt(List<Pattern> options) implements Pattern {
    }

    record Empty() implements Pattern {
    }

    /** 基数：min/max 次数，max=INF 即闭包 */
    record Many(Pattern body, int min, int max) implements Pattern {
    }

    int INF = Integer.MAX_VALUE;

    static Ref ref(String name) {
        return new Ref(name);
    }

    static Token lit(String literal) {
        return new Token(literal);
    }

    static Seq seq(Pattern... parts) {
        return new Seq(List.of(parts));
    }

    static Alt alt(Pattern... options) {
        return new Alt(List.of(options));
    }

    static Empty empty() {
        return new Empty();
    }

    /** * 闭包：零或多，贪婪 */
    static Many star(Pattern body) {
        return new Many(body, 0, INF);
    }

    /** + 正闭包：一或多，贪婪 */
    static Many plus(Pattern body) {
        return new Many(body, 1, INF);
    }

    /** ? 可选：零或一 */
    static Many opt(Pattern body) {
        return new Many(body, 0, 1);
    }
}
