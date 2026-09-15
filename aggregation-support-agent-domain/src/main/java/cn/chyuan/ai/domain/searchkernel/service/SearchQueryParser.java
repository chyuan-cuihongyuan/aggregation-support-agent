package cn.chyuan.ai.domain.searchkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询解析器（工单 0402 AW7）。
 * 查询串 → 解析树：普通词 / 引号短语 / 前缀词* / -排除词 / facet:值 过滤子句；
 * 引号未闭合降级为普通词+告警标记；过滤子句结构化输出。纯函数。
 */
public class SearchQueryParser {

    /** 解析结果 */
    public record Query(List<String> terms, List<String> phrases, List<String> prefixes,
                        List<String> excluded, Map<String, String> filters, List<String> warnings) {
    }

    private final SearchTokenizer tokenizer = new SearchTokenizer();

    /**
     * 解析：token 以空白切分；引号包裹为短语（内部不再分词，交给调用方）；-开头为排除；*结尾为前缀；含:为过滤子句。
     */
    public Query parse(String query) {
        List<String> terms = new ArrayList<>();
        List<String> phrases = new ArrayList<>();
        List<String> prefixes = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        Map<String, String> filters = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        if (query == null || query.isBlank()) {
            return new Query(terms, phrases, prefixes, excluded, filters, warnings);
        }
        List<String> tokens = splitRespectingQuotes(query, warnings);
        for (String token : tokens) {
            if (token.startsWith("\"")) {
                if (token.endsWith("\"") && token.length() >= 2) {
                    String phrase = token.substring(1, token.length() - 1).strip();
                    if (!phrase.isEmpty()) {
                        phrases.add(phrase);
                    }
                } else {
                    warnings.add("引号未闭合，降级为普通词: " + token);
                    for (String term : tokenizer.tokenize(token.replace("\"", " "))) {
                        terms.add(term);
                    }
                }
            } else if (token.startsWith("-") && token.length() > 1) {
                excluded.add(token.substring(1));
            } else if (token.endsWith("*") && token.length() > 1) {
                prefixes.add(token.substring(0, token.length() - 1));
            } else if (token.contains(":")) {
                int colon = token.indexOf(':');
                String field = token.substring(0, colon);
                String value = token.substring(colon + 1);
                if (field.isBlank() || value.isBlank()) {
                    warnings.add("非法过滤子句忽略: " + token);
                } else {
                    filters.put(field, value);
                }
            } else {
                for (String term : tokenizer.tokenize(token)) {
                    terms.add(term);
                }
            }
        }
        return new Query(terms, phrases, prefixes, excluded, filters, warnings);
    }

    /** 引号感知切分：成对引号内含空白为一个 token；未闭合引号到串尾为一个 token（交由上方降级） */
    private List<String> splitRespectingQuotes(String query, List<String> warnings) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        for (char ch : query.toCharArray()) {
            if (ch == '"') {
                inQuote = !inQuote;
                current.append(ch);
            } else if (Character.isWhitespace(ch) && !inQuote) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(ch);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
