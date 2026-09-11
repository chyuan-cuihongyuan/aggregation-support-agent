package cn.chyuan.ai.domain.rag.service.query;

import cn.chyuan.ai.domain.rag.adapter.port.IQueryRewritePort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 查询改写规则版实现（工单 0165，W3）— 内置停用词表 + 同义扩展表
 * <p>
 * 纯函数内核（domain 零框架依赖、无状态），流程：
 * <ol>
 *   <li>同义扩展：query 中出现的同义 key 替换为标准词（英文按词边界，中文直接子串）</li>
 *   <li>停用词过滤：英文整词与中文口语字/词剔除，剩余 token 按原序重组</li>
 * </ol>
 * 停用词/同义表支持构造注入（infrastructure 装配时从配置解析传入），
 * 未注入部分自动回退内置默认表。空串 / null 原样保真。
 */
public class RuleBasedQueryRewriter implements IQueryRewritePort {

    /** 分词口径与 TraceQualityCalculator 一致：保留小写字母/数字/中文，其余作为分隔 */
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9\\u4e00-\\u9fa5]+");
    private static final Pattern CHINESE = Pattern.compile("[\\u4e00-\\u9fa5]");
    private static final Pattern ENGLISH_WORD = Pattern.compile("^[a-z0-9]+$");

    /** 内置英文停用词（小写整词） */
    public static final Set<String> DEFAULT_ENGLISH_STOPWORDS = Set.of(
            "the", "a", "an", "is", "are", "am", "was", "were", "be", "been", "being",
            "do", "does", "did", "of", "to", "in", "on", "at", "for", "with", "about",
            "and", "or", "but", "so", "please", "tell", "me", "what", "which", "who",
            "how", "can", "could", "would", "should", "i", "you", "he", "she", "it", "we", "they");

    /** 内置中文停用字（逐字过滤，均为虚词/语气字；不收"么"以免误伤"什么/为什么"） */
    public static final Set<String> DEFAULT_CHINESE_STOP_CHARS = Set.of(
            "的", "了", "吗", "呢", "吧", "啊", "呀", "哦", "嘛", "哟", "咯", "捏");

    /** 内置中文停用词（段内子串整体移除，口语引导词，独立性强不易误伤实词） */
    public static final Set<String> DEFAULT_CHINESE_STOPWORDS = Set.of(
            "请问", "帮我", "帮忙", "告诉我", "一下", "一些", "这个", "那个", "怎么样", "如何看");

    /** 内置同义扩展表（口语/缩写 → 标准检索词） */
    public static final Map<String, String> DEFAULT_SYNONYMS = defaultSynonyms();

    /** 停用词全集（英文整词） */
    private final Set<String> englishStopwords;

    /** 中文停用词（多字，段内子串移除，按长度降序应用） */
    private final List<String> chineseStopwordsByLength;

    /** 中文停用字（单字） */
    private final Set<String> chineseStopChars;

    /** 同义扩展表（key 长度降序应用，避免短 key 先替换破坏长 key） */
    private final List<Map.Entry<String, String>> synonymsByLength;

    public RuleBasedQueryRewriter() {
        this(null, null);
    }

    /**
     * @param stopwords 额外停用词（为 null 时仅用内置表）
     * @param synonyms  同义扩展表（为 null 时仅用内置表；配置注入入口）
     */
    public RuleBasedQueryRewriter(Set<String> stopwords, Map<String, String> synonyms) {
        Set<String> english = new LinkedHashSet<>(DEFAULT_ENGLISH_STOPWORDS);
        Set<String> zhChars = new LinkedHashSet<>(DEFAULT_CHINESE_STOP_CHARS);
        Set<String> zhWords = new LinkedHashSet<>(DEFAULT_CHINESE_STOPWORDS);
        if (stopwords != null) {
            for (String word : stopwords) {
                if (word == null || word.isBlank()) {
                    continue;
                }
                String w = word.trim().toLowerCase();
                if (ENGLISH_WORD.matcher(w).matches()) {
                    english.add(w);
                } else if (w.length() == 1 && CHINESE.matcher(w).find()) {
                    zhChars.add(w);
                } else {
                    zhWords.add(w);
                }
            }
        }
        this.englishStopwords = english;
        this.chineseStopChars = zhChars;
        this.chineseStopwordsByLength = new ArrayList<>(zhWords);
        this.chineseStopwordsByLength.sort(Comparator.comparingInt(String::length).reversed());

        Map<String, String> merged = new LinkedHashMap<>(DEFAULT_SYNONYMS);
        if (synonyms != null) {
            synonyms.forEach((k, v) -> {
                if (k != null && v != null && !k.isBlank() && !v.isBlank()) {
                    merged.put(k.trim().toLowerCase(), v.trim());
                }
            });
        }
        this.synonymsByLength = new ArrayList<>(merged.entrySet());
        // 按 key 长度降序：长 key 先替换，避免短 key 先行破坏长 key 匹配
        this.synonymsByLength.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed());
    }

    @Override
    public String rewrite(String query) {
        // 空串 / null 保真（端口契约）
        if (query == null || query.isBlank()) {
            return query;
        }

        // 1. 同义扩展
        String rewritten = applySynonyms(query.toLowerCase());

        // 2. 停用词过滤 + 原序重组
        rewritten = removeStopwords(rewritten).trim();

        // 无有效产出（全停用词）时保真返回原 query，避免空查询进检索
        return rewritten.isEmpty() ? query : rewritten;
    }

    /** 同义扩展：英文 key 按词边界替换，含中文的 key 直接子串替换 */
    private String applySynonyms(String text) {
        String result = text;
        for (Map.Entry<String, String> entry : synonymsByLength) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (ENGLISH_WORD.matcher(key).matches()) {
                result = Pattern.compile("\\b" + java.util.regex.Pattern.quote(key) + "\\b")
                        .matcher(result).replaceAll(Matcher.quoteReplacement(value.toLowerCase()));
            } else {
                result = result.replace(key, value.toLowerCase());
            }
        }
        return result;
    }

    /** 停用词过滤：中文段先删多字停用词再逐字删停用字；英文 token 整词过滤；按原序重组 */
    private String removeStopwords(String text) {
        String[] segments = TOKEN_SPLIT.split(text);
        StringBuilder result = new StringBuilder();
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            if (CHINESE.matcher(segment).find()) {
                // 中文段：多字停用词 → 单字停用字
                String processed = segment;
                for (String word : chineseStopwordsByLength) {
                    processed = processed.replace(word, "");
                }
                StringBuilder zh = new StringBuilder();
                for (int i = 0; i < processed.length(); i++) {
                    String ch = String.valueOf(processed.charAt(i));
                    if (!chineseStopChars.contains(ch)) {
                        zh.append(ch);
                    }
                }
                if (zh.length() > 0) {
                    if (result.length() > 0) {
                        result.append(' ');
                    }
                    result.append(zh);
                }
            } else if (!englishStopwords.contains(segment)) {
                if (result.length() > 0) {
                    result.append(' ');
                }
                result.append(segment);
            }
        }
        return result.toString();
    }

    private static Map<String, String> defaultSynonyms() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("js", "JavaScript");
        map.put("啥", "什么");
        map.put("大模型", "LLM");
        map.put("向量库", "向量数据库");
        return map;
    }
}
