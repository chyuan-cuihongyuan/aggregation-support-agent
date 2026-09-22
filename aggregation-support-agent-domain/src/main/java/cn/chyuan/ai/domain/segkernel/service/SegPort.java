package cn.chyuan.ai.domain.segkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 分词端口+组合管线（工单 0532 BK8）。
 * SegPort（文本→词序列/关键词 topN）+内存假实现+组合管线
 * （装载词典→分词→高频词条登记）+与 textkernel 分析器只读联动
 * （词序列作为分析输入形态，不修改 textkernel 任何类）/
 * seg-kernel.enabled 默认关（开启才改变行为）。
 */
public interface SegPort {

    /** 装载基础词典（增量合并） */
    void loadDict(Map<String, Long> dict);

    /** 追加用户词（强制成词） */
    void addUserWord(String word, long freq);

    /** 追加屏蔽词（强制切开） */
    void blockWord(String word);

    /** 停用词（关键词与分析联动面过滤） */
    void addStopword(String word);

    /** 精确切分（含 HMM 未知词） */
    List<String> segment(String text);

    /** 关键词 topN（TextRank） */
    List<String> keywords(String text, int topN);

    /** 高频词条登记进词条表（组合管线出口：登记最近一次切分） */
    void registerTopTerms();

    /**
     * 与 textkernel 分析器只读联动：把切分词序列作为检索分析的可选输入形态
     * （词级 token 流，去停用词），供检索面按词索引——只读转换，不改 textkernel。
     */
    List<String> toAnalyzerInput(String text);

    /** 词条表访问（组合管线出口校验用） */
    SegTermRegistry registry();

    /** 内存假实现：TrieDict + UserDictOverlay + SegModes + KeywordExtractor + SegTermRegistry */
    class InMemorySegmenter implements SegPort {

        private final TrieDict dict = new TrieDict();
        private final UserDictOverlay overlay = new UserDictOverlay();
        private final SegModes modes = new SegModes();
        private final KeywordExtractor extractor = new KeywordExtractor();
        private final SegTermRegistry registry = new SegTermRegistry();
        private final Set<String> stopwords = new LinkedHashSet<>();
        private List<String> lastSegmented = List.of();

        @Override
        public synchronized void loadDict(Map<String, Long> dictMap) {
            if (dictMap == null) {
                throw new IllegalArgumentException("词典不得为 null");
            }
            for (Map.Entry<String, Long> entry : new LinkedHashMap<>(dictMap).entrySet()) {
                dict.add(entry.getKey(), entry.getValue());
            }
        }

        @Override
        public synchronized void addUserWord(String word, long freq) {
            overlay.addWord(word, freq);
        }

        @Override
        public synchronized void blockWord(String word) {
            overlay.blockWord(word);
        }

        @Override
        public synchronized void addStopword(String word) {
            stopwords.add(word);
        }

        @Override
        public synchronized List<String> segment(String text) {
            List<String> words = modes.exact(dict, overlay, text, true);
            this.lastSegmented = words;
            return words;
        }

        @Override
        public synchronized List<String> keywords(String text, int topN) {
            List<String> words = segment(text);
            List<KeywordExtractor.Keyword> ranked =
                    extractor.textrank(words, topN, stopwords, 4, 0.85d, 1.0E-6d, 100);
            List<String> out = new ArrayList<>();
            for (KeywordExtractor.Keyword keyword : ranked) {
                out.add(keyword.word());
            }
            return List.copyOf(out);
        }

        @Override
        public synchronized void registerTopTerms() {
            if (lastSegmented.isEmpty()) {
                throw new IllegalStateException("须先 segment 再登记高频词条");
            }
            Map<String, Long> freq = new LinkedHashMap<>();
            for (String word : lastSegmented) {
                freq.merge(word, 1L, Long::sum);
            }
            for (Map.Entry<String, Long> entry : freq.entrySet()) {
                String source = overlay.isUser(entry.getKey()) ? SegTermRegistry.SOURCE_USER
                        : SegTermRegistry.SOURCE_DICT;
                registry.register(entry.getKey(), entry.getValue(), source);
            }
        }

        @Override
        public synchronized List<String> toAnalyzerInput(String text) {
            List<String> words = segment(text);
            List<String> tokens = new ArrayList<>();
            for (String word : words) {
                if (!stopwords.contains(word)) {
                    tokens.add(word);
                }
            }
            return List.copyOf(tokens);
        }

        @Override
        public synchronized SegTermRegistry registry() {
            return registry;
        }
    }
}
