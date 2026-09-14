package cn.chyuan.ai.domain.knowledgegraph.graphrag.service;

import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.AssertionProvenanceVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphEdgeVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphIndexVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.ProvenanceChainVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.TextUnitVO;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 答案引用溯源（工单 0312 AM7）。
 * 答案按句切分断言 → 每断言映射证据锚点：实体名包含命中（E:）、
 * 关系两端同现命中（R:）、文本块关键词重叠阈值命中（U:）。
 * 无支撑断言标记 unanchored，graphrag citation 思想的确定性简化。
 */
public class AnswerProvenance {

    private final double unitOverlapThreshold;

    public AnswerProvenance(double unitOverlapThreshold) {
        if (unitOverlapThreshold <= 0 || unitOverlapThreshold > 1) {
            throw new IllegalArgumentException("重叠阈值应在 (0,1] 区间");
        }
        this.unitOverlapThreshold = unitOverlapThreshold;
    }

    public ProvenanceChainVO chain(String answer, GraphIndexVO index) {
        List<String> sentences = splitAssertions(answer);
        List<AssertionProvenanceVO> assertions = new ArrayList<>();
        int anchored = 0;
        for (String sentence : sentences) {
            List<String> anchors = findAnchors(sentence, index);
            boolean unanchored = anchors.isEmpty();
            if (!unanchored) {
                anchored++;
            }
            assertions.add(AssertionProvenanceVO.builder()
                    .text(sentence)
                    .anchors(anchors)
                    .unanchored(unanchored)
                    .build());
        }
        double rate = sentences.isEmpty() ? 0.0 : (double) anchored / sentences.size();
        return ProvenanceChainVO.builder()
                .assertions(assertions)
                .anchoredRate(rate)
                .build();
    }

    /** 句切分：中英文句末标点+换行，去空白，保留非空句 */
    List<String> splitAssertions(String answer) {
        if (answer == null || answer.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.stream(answer.split("(?<=[。！？!?.;；\n])"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private List<String> findAnchors(String sentence, GraphIndexVO index) {
        Set<String> anchors = new LinkedHashSet<>();
        String normalized = GraphIndexBuilder.normalize(sentence);
        if (normalized.isEmpty()) {
            return new ArrayList<>();
        }
        // 实体锚点：实体键被断言包含
        index.getNodes().forEach(node -> {
            if (!node.getNodeKey().isEmpty() && normalized.contains(node.getNodeKey())) {
                anchors.add("E:" + node.getNodeKey());
            }
        });
        // 关系锚点：两端实体键同现
        for (GraphEdgeVO edge : index.getEdges()) {
            if (normalized.contains(edge.getSourceKey()) && normalized.contains(edge.getTargetKey())) {
                anchors.add("R:" + edge.getEdgeKey());
            }
        }
        // 文本块锚点：关键词（块内去重词集）重叠率达标
        for (TextUnitVO unit : index.getTextUnits()) {
            if (overlapRatio(normalized, GraphIndexBuilder.normalize(unit.getContent()))
                    >= unitOverlapThreshold) {
                anchors.add("U:" + unit.getUnitId());
            }
        }
        return new ArrayList<>(anchors);
    }

    /** 字符 bigram 集合重叠率：|断言 ∩ 块| / |断言| */
    static double overlapRatio(String normalizedSentence, String normalizedUnit) {
        Set<String> sentenceGrams = bigrams(normalizedSentence);
        if (sentenceGrams.isEmpty()) {
            return 0.0;
        }
        Set<String> unitGrams = bigrams(normalizedUnit);
        long hit = sentenceGrams.stream().filter(unitGrams::contains).count();
        return (double) hit / sentenceGrams.size();
    }

    private static Set<String> bigrams(String text) {
        Set<String> grams = new LinkedHashSet<>();
        for (int i = 0; i + 1 < text.length(); i++) {
            grams.add(text.substring(i, i + 2));
        }
        return grams;
    }
}
