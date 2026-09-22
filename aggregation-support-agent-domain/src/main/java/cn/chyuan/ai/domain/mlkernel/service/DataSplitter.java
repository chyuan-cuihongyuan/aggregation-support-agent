package cn.chyuan.ai.domain.mlkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 数据划分（工单 0541 BM1，sklearn train_test_split 思想）。
 * 固定种子随机端口（java.util.Random(seed)）洗牌/训练测试比例切分/
 * 分层抽样按类比例保持/同种子两次划分完全一致/非法比例拒绝。
 */
public final class DataSplitter {

    /** 划分结果：训练/测试下标（升序输出） */
    public record Split(int[] train, int[] test) {
    }

    private final long seed;

    public DataSplitter(long seed) {
        this.seed = seed;
    }

    public long seed() {
        return seed;
    }

    /** 简单随机划分（testRatio ∈ (0,1)） */
    public Split split(int n, double testRatio) {
        requireRatio(testRatio);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        shuffle(order, new Random(seed));
        int testCount = (int) Math.round(n * testRatio);
        List<Integer> test = new ArrayList<>(order.subList(0, testCount));
        List<Integer> train = new ArrayList<>(order.subList(testCount, n));
        return new Split(ascending(train), ascending(test));
    }

    /** 分层抽样：逐类洗牌后按类内 round(count×ratio) 抽测试集 */
    public Split splitStratified(int[] labels, double testRatio) {
        requireRatio(testRatio);
        Map<Integer, List<Integer>> byClass = new LinkedHashMap<>();
        for (int i = 0; i < labels.length; i++) {
            byClass.computeIfAbsent(labels[i], k -> new ArrayList<>()).add(i);
        }
        Random random = new Random(seed);
        List<Integer> train = new ArrayList<>();
        List<Integer> test = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> entry : byClass.entrySet()) {
            List<Integer> idx = new ArrayList<>(entry.getValue());
            shuffle(idx, random);
            int testCount = (int) Math.round(idx.size() * testRatio);
            test.addAll(idx.subList(0, testCount));
            train.addAll(idx.subList(testCount, idx.size()));
        }
        return new Split(ascending(train), ascending(test));
    }

    private void shuffle(List<Integer> list, Random random) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
    }

    private int[] ascending(List<Integer> indices) {
        int[] out = indices.stream().mapToInt(Integer::intValue).toArray();
        java.util.Arrays.sort(out);
        return out;
    }

    private void requireRatio(double testRatio) {
        if (testRatio <= 0.0d || testRatio >= 1.0d) {
            throw new IllegalArgumentException("测试比例须在 (0,1) 开区间: " + testRatio);
        }
    }
}
