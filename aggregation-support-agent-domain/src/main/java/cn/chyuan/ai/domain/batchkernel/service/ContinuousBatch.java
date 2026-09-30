package cn.chyuan.ai.domain.batchkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 迭代级连续批（工单 1060 EO3 + 1062 EO5，vllm 思想）。
 * 新请求 prefill 与既有 decode 混批共存/批大小上限拒绝超纳/完成逐出/批内成员序稳定（LinkedHashSet）。
 */
public final class ContinuousBatch {

    private final int maxBatchSize;
    private final LinkedHashSet<String> members = new LinkedHashSet<>();

    public ContinuousBatch(int maxBatchSize) {
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("批大小上限必须为正: " + maxBatchSize);
        }
        this.maxBatchSize = maxBatchSize;
    }

    /** 纳入批；超上限拒绝，重复纳入幂等 */
    public void admit(String id) {
        if (members.size() >= maxBatchSize && !members.contains(id)) {
            throw new IllegalStateException("批大小上限 " + maxBatchSize + " 拒绝超纳: " + id);
        }
        members.add(id);
    }

    /** 逐出批 */
    public void evict(String id) {
        members.remove(id);
    }

    public boolean contains(String id) {
        return members.contains(id);
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }

    public int size() {
        return members.size();
    }

    public List<String> ids() {
        return new ArrayList<>(members);
    }
}
