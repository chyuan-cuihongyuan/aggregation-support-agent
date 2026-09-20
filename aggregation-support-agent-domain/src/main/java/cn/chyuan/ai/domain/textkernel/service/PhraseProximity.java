package cn.chyuan.ai.domain.textkernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 短语与邻近查询（工单 0490 BG3，elasticsearch match_phrase 思想）。
 * 位置倒排（词元 → 文档 → 位置表）/短语连续命中（position 严格递增 1）/
 * slop 词距容忍匹配（允许总位移 ≤ slop 的有序跳变）。
 */
public class PhraseProximity {

    /** 命中：文档 + 起始位置 */
    public record PhraseHit(int docId, int startPosition) {
    }

    /** 位置倒排：term → docId → 位置列表（升序） */
    private final Map<String, Map<Integer, List<Integer>>> positionalIndex = new HashMap<>();

    /** 登记文档词元（position 用词元 position 字段） */
    public synchronized void indexDoc(int docId, List<AnalyzerChain.Token> tokens) {
        for (AnalyzerChain.Token token : tokens) {
            positionalIndex.computeIfAbsent(token.text(), k -> new HashMap<>())
                    .computeIfAbsent(docId, k -> new ArrayList<>()).add(token.position());
        }
    }

    /** 短语连续命中：词序列 position 严格 +1 */
    public synchronized List<PhraseHit> phrase(List<String> terms) {
        return match(terms, 0);
    }

    /** slop 容忍匹配：允许总位移 ≤ slop（有序跳变，位置差 - 1 ≤ slop 累计） */
    public synchronized List<PhraseHit> phraseWithSlop(List<String> terms, int slop) {
        if (slop < 0) {
            throw new IllegalArgumentException("slop 须 ≥ 0");
        }
        return match(terms, slop);
    }

    private synchronized List<PhraseHit> match(List<String> terms, int slop) {
        if (terms.isEmpty()) {
            return List.of();
        }
        Map<Integer, List<Integer>> first = positionalIndex.getOrDefault(terms.get(0), Map.of());
        List<PhraseHit> hits = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> entry : first.entrySet()) {
            int docId = entry.getKey();
            for (int start : entry.getValue()) {
                if (matchesFrom(docId, terms, start, slop)) {
                    hits.add(new PhraseHit(docId, start));
                    break;
                }
            }
        }
        hits.sort(java.util.Comparator.comparingInt(PhraseHit::docId));
        return hits;
    }

    /** 从 start 起依次匹配后续词（各词位置须递增，累计位移 ≤ slop） */
    private boolean matchesFrom(int docId, List<String> terms, int start, int slop) {
        int previousPos = start;
        int drift = 0;
        for (int i = 1; i < terms.size(); i++) {
            List<Integer> positions = positionalIndex.getOrDefault(terms.get(i), Map.of())
                    .getOrDefault(docId, List.of());
            int next = Integer.MIN_VALUE;
            for (int pos : positions) {
                if (pos > previousPos) {
                    next = pos;
                    break;
                }
            }
            if (next == Integer.MIN_VALUE) {
                return false;
            }
            drift += next - previousPos - 1;
            if (drift > slop) {
                return false;
            }
            previousPos = next;
        }
        return true;
    }
}
