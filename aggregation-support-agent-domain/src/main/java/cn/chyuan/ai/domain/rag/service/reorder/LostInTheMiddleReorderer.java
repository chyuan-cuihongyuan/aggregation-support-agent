package cn.chyuan.ai.domain.rag.service.reorder;

import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Lost in the Middle 处器 — 优化chunk排列顺序，提升LLM对关键内容的关注度
 * <p>
 * 问题背景：
 * LLM处理长文本时，对开头和结尾的内容关注度高，中间内容容易被忽略（Lost in the Middle）。
 * <p>
 * 解决方案：
 * 将最相关的内容放在首尾位置，次相关内容放在中间。
 * <p>
 * 排列策略：
 * <ul>
 *   <li>第1个位置：最相关的内容（rank 1）</li>
 *   <li>第2个位置：第三相关的内容（rank 3）</li>
 *   <li>第3个位置：第五相关的内容（rank 5）</li>
 *   <li>...依此类推</li>
 *   <li>倒数第2个位置：第四相关的内容（rank 4）</li>
 *   <li>倒数第1个位置：第二相关的内容（rank 2）</li>
 * </ul>
 * <p>
 * 示例：
 * 原始顺序：[1, 2, 3, 4, 5, 6, 7, 8]
 * 优化顺序：[1, 3, 5, 7, 8, 6, 4, 2]
 */
@Slf4j
@Service
public class LostInTheMiddleReorderer {

    /**
     * 对检索结果进行Lost in the Middle重排
     *
     * @param results 原始检索结果（已按相关度排序）
     * @return 重排后的结果
     */
    public List<VectorSearchResultVO> reorder(List<VectorSearchResultVO> results) {
        if (results == null || results.size() <= 2) {
            return results;
        }

        log.info("Lost in the Middle重排: inputSize={}", results.size());

        List<VectorSearchResultVO> reordered = new ArrayList<>();
        int size = results.size();

        // 奇数索引的元素放前面（第1、3、5...个）
        for (int i = 0; i < size; i += 2) {
            reordered.add(results.get(i));
        }

        // 偶数索引的元素放后面，倒序排列（第...、4、2个）
        for (int i = (size % 2 == 0 ? size - 1 : size - 2); i >= 1; i -= 2) {
            reordered.add(results.get(i));
        }

        log.debug("Lost in the Middle重排完成: original={}, reordered={}",
                getOrderIndices(results), getOrderIndices(reordered));

        return reordered;
    }

    /**
     * 带权重的Lost in the Middle重排
     * <p>
     * 根据chunk的相关度分数动态调整位置权重。
     * 排列策略：
     * <ul>
     *   <li>头部（headWeight 比例）：放最相关的内容（rank 1, 2, ...）</li>
     *   <li>尾部（tailWeight 比例）：放次相关的内容（紧跟 head 之后的部分）</li>
     *   <li>中间（剩余部分）：放相关度最低的内容</li>
     * </ul>
     * 这样 LLM 在处理长上下文时，对首尾位置的高关注度能覆盖到最相关和次相关的内容。
     *
     * @param results 原始检索结果（已按相关度排序，最相关在前）
     * @param headWeight 开头位置的权重（默认0.4）
     * @param tailWeight 结尾位置的权重（默认0.4）
     * @return 重排后的结果
     */
    public List<VectorSearchResultVO> reorderWithWeight(
            List<VectorSearchResultVO> results,
            double headWeight,
            double tailWeight) {

        if (results == null || results.size() <= 2) {
            return results;
        }

        log.info("带权重的Lost in the Middle重排: inputSize={}, headWeight={}, tailWeight={}",
                results.size(), headWeight, tailWeight);

        int size = results.size();
        int headCount = (int) Math.round(size * headWeight);
        int tailCount = (int) Math.round(size * tailWeight);
        int middleCount = size - headCount - tailCount;

        // 确保各区域至少有1个元素
        if (headCount == 0) headCount = 1;
        if (tailCount == 0) tailCount = 1;
        if (middleCount < 0) {
            // head + tail 超过总数时，压缩 tail
            tailCount = size - headCount;
            middleCount = 0;
        }

        List<VectorSearchResultVO> reordered = new ArrayList<>();

        // 头部：放最相关的内容 results[0..headCount-1]
        for (int i = 0; i < headCount && i < size; i++) {
            reordered.add(results.get(i));
        }

        // 中间：放相关度最低的内容 results[headCount+tailCount..size-1]
        for (int i = headCount + tailCount; i < size; i++) {
            reordered.add(results.get(i));
        }

        // 尾部：放次相关的内容 results[headCount..headCount+tailCount-1]
        for (int i = headCount; i < headCount + tailCount && i < size; i++) {
            reordered.add(results.get(i));
        }

        log.debug("带权重重排完成: headCount={}, middleCount={}, tailCount={}",
                headCount, middleCount, tailCount);

        return reordered;
    }

    /**
     * 获取结果的索引顺序（用于调试）
     */
    private List<Integer> getOrderIndices(List<VectorSearchResultVO> results) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            indices.add(i);
        }
        return indices;
    }

}
