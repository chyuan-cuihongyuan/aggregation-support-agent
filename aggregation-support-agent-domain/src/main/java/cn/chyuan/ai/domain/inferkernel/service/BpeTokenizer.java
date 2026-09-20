package cn.chyuan.ai.domain.inferkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BPE 分词（工单 0504 BI1，llama.cpp/tiktoken BPE 子集思想）。
 * 词表 + 合并规则子集（相邻 pair 按优先级合并）/编码解码往返等价/
 * 未知字符字节回退（UTF-8 字节级拆分为字节 token）。
 */
public class BpeTokenizer {

    /** token：文本 + id（词表未收录的文本 id 为 -1） */
    public record Token(String text, int id) {
    }

    /** 词表：文本 → id */
    private final Map<String, Integer> vocabulary;
    /** 合并优先级：pair 序列化 → 秩（小者先合并） */
    private final Map<String, Integer> mergeRanks;

    public BpeTokenizer(Map<String, Integer> vocabulary, Map<String, Integer> mergeRanks) {
        this.vocabulary = new LinkedHashMap<>(vocabulary);
        this.mergeRanks = new LinkedHashMap<>(mergeRanks);
    }

    /** 编码：先按字符切分，未知字符回退为 UTF-8 字节 token，再按合并秩贪心合并 */
    public List<Token> encode(String text) {
        List<String> pieces = new ArrayList<>();
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            String ch = new String(Character.toChars(codePoint));
            if (vocabulary.containsKey(ch)) {
                pieces.add(ch);
            } else {
                for (byte b : ch.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                    pieces.add(byteToken(b));
                }
            }
            i += Character.charCount(codePoint);
        }
        while (true) {
            String bestPair = null;
            int bestRank = Integer.MAX_VALUE;
            for (int i = 0; i < pieces.size() - 1; i++) {
                String pair = pieces.get(i) + '\u0000' + pieces.get(i + 1);
                Integer rank = mergeRanks.get(pair);
                if (rank != null && rank < bestRank) {
                    bestRank = rank;
                    bestPair = pair;
                }
            }
            if (bestPair == null) {
                break;
            }
            String[] parts = bestPair.split("\u0000", -1);
            List<String> merged = new ArrayList<>();
            for (int i = 0; i < pieces.size(); i++) {
                if (i < pieces.size() - 1 && pieces.get(i).equals(parts[0]) && pieces.get(i + 1).equals(parts[1])) {
                    merged.add(parts[0] + parts[1]);
                    i++;
                } else {
                    merged.add(pieces.get(i));
                }
            }
            pieces = merged;
        }
        return pieces.stream().map(piece -> new Token(piece, vocabulary.getOrDefault(piece, -1))).toList();
    }

    /** 解码：token 文本拼接（往返等价由测试断言） */
    public String decode(List<Token> tokens) {
        StringBuilder text = new StringBuilder();
        for (Token token : tokens) {
            text.append(token.text());
        }
        return text.toString();
    }

    public int vocabSize() {
        return vocabulary.size();
    }

    /** 字节回退 token 文本（可打印化：〈0xHH〉） */
    static String byteToken(byte b) {
        return String.format("<0x%02X>", b);
    }
}
