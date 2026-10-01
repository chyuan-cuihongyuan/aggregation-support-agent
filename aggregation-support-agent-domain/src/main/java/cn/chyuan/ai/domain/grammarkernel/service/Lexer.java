package cn.chyuan.ai.domain.grammarkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 词法器（工单 1221 FH2，antlr 思想）。
 * 候选字面量 = 显式 TOKEN 字面量（TOKEN 声明序优先）+ 隐式字面量（首现序）；
 * 最长匹配优先，同长先声明者胜；未匹配字符报词法错误并跳过该字符（恢复继续）。
 */
public final class Lexer {

    /** 词法符号：type=显式 TOKEN 名或隐式字面量自身 */
    public record Token(String type, String text) {
        static final Token EOF = new Token("<EOF>", "<EOF>");
    }

    public record LexError(int index, String message) {
    }

    public record LexResult(List<Token> tokens, List<LexError> errors) {
    }

    /** 切词：显式 TOKEN 与隐式字面量统一按最长匹配 */
    public LexResult tokenize(Grammar grammar, String input) {
        List<String> names = new ArrayList<>();
        List<String> literals = new ArrayList<>();
        for (String tokenName : grammar.tokenNames()) {
            for (String literal : grammar.explicitTokenLiterals(tokenName)) {
                names.add(tokenName);
                literals.add(literal);
            }
        }
        for (String implicit : grammar.implicits()) {
            names.add(implicit);
            literals.add(implicit);
        }
        List<Token> tokens = new ArrayList<>();
        List<LexError> errors = new ArrayList<>();
        int pos = 0;
        while (pos < input.length()) {
            int bestLen = 0;
            String bestType = null;
            for (int i = 0; i < literals.size(); i++) {
                String literal = literals.get(i);
                if (input.startsWith(literal, pos) && literal.length() > bestLen) {
                    bestLen = literal.length();
                    bestType = names.get(i);
                }
            }
            if (bestType == null) {
                errors.add(new LexError(pos, "无法识别的字符 '" + input.charAt(pos) + "'"));
                pos++;
                continue;
            }
            tokens.add(new Token(bestType, input.substring(pos, pos + bestLen)));
            pos += bestLen;
        }
        tokens.add(Token.EOF);
        return new LexResult(tokens, errors);
    }

    /** 字面量到所属 TOKEN 的映射（供 ParserEngine 类型匹配） */
    public Map<String, String> literalOwners(Grammar grammar) {
        Map<String, String> owners = new LinkedHashMap<>();
        for (String tokenName : grammar.tokenNames()) {
            for (String literal : grammar.explicitTokenLiterals(tokenName)) {
                owners.putIfAbsent(literal, tokenName);
            }
        }
        return owners;
    }
}
