package cn.chyuan.ai.domain.segkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分词词条登记（工单 0531 BK7，第 33 表 seg_term 的内核镜像面）。
 * 词条唯一键（重复登记更新词频）/来源 DICT-USER-HMM/状态 ACTIVE-DELETED/
 * 词长派生；快照按登记序输出。seg-kernel.enabled 默认关。
 */
public final class SegTermRegistry {

    public static final String SOURCE_DICT = "DICT";
    public static final String SOURCE_USER = "USER";
    public static final String SOURCE_HMM = "HMM";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DELETED = "DELETED";

    /** 词条行（word 唯一键；wordLength 派生） */
    public record Term(String word, long freq, int wordLength, String source, String status) {
    }

    private final Map<String, Term> terms = new LinkedHashMap<>();

    /** 登记词条（重复词更新词频与来源；空词拒绝） */
    public void register(String word, long freq, String source) {
        if (word == null || word.isEmpty()) {
            throw new IllegalArgumentException("词条不得为空");
        }
        if (freq <= 0) {
            throw new IllegalArgumentException("词频须为正: " + word);
        }
        if (!SOURCE_DICT.equals(source) && !SOURCE_USER.equals(source) && !SOURCE_HMM.equals(source)) {
            throw new IllegalArgumentException("非法来源: " + source);
        }
        terms.put(word, new Term(word, freq, word.length(), source, STATUS_ACTIVE));
    }

    /** 注销词条（墓碑保序） */
    public void delete(String word) {
        Term cur = terms.get(word);
        if (cur != null) {
            terms.put(word, new Term(cur.word(), cur.freq(), cur.wordLength(), cur.source(), STATUS_DELETED));
        }
    }

    public boolean contains(String word) {
        Term term = terms.get(word);
        return term != null && STATUS_ACTIVE.equals(term.status());
    }

    /** 按词频降序、同频字典序取 topN（仅 ACTIVE） */
    public List<Term> topByFreq(int topN) {
        List<Term> active = new ArrayList<>();
        for (Term term : terms.values()) {
            if (STATUS_ACTIVE.equals(term.status())) {
                active.add(term);
            }
        }
        active.sort((a, b) -> {
            int byFreq = Long.compare(b.freq(), a.freq());
            return byFreq != 0 ? byFreq : a.word().compareTo(b.word());
        });
        return List.copyOf(active.subList(0, Math.min(topN, active.size())));
    }

    /** 全量快照（登记序，含墓碑） */
    public List<Term> snapshot() {
        return List.copyOf(terms.values());
    }

    public int size() {
        return terms.size();
    }
}
