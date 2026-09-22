package cn.chyuan.ai.domain.mlkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 决策树（工单 0545 BM5，sklearn DecisionTreeClassifier 思想）。
 * 基尼与熵两种分裂准则（枚举特征×有序中点阈值，首个最优胜出确定性）/
 * 最大深度与叶子最小样本剪枝约束/特征重要性（不纯度减少按节点样本占比归一）/
 * 预测多数类/单类节点叶化。
 */
public final class DecisionTree {

    public enum Criterion {
        GINI, ENTROPY
    }

    private static final class Node {
        boolean leaf;
        int prediction;
        int feature = -1;
        double threshold;
        Node left;
        Node right;
        double impurityDecrease;
        int samples;
    }

    private final Criterion criterion;
    private final int maxDepth;
    private final int minSamplesLeaf;
    private Node root;
    private int rootSamples;

    public DecisionTree(Criterion criterion, int maxDepth, int minSamplesLeaf) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("最大深度须≥1");
        }
        if (minSamplesLeaf < 1) {
            throw new IllegalArgumentException("叶子最小样本须≥1");
        }
        this.criterion = criterion;
        this.maxDepth = maxDepth;
        this.minSamplesLeaf = minSamplesLeaf;
    }

    /** 拟合（返回 this 便于链式） */
    public DecisionTree fit(double[][] x, int[] y) {
        if (x.length == 0 || x.length != y.length) {
            throw new IllegalArgumentException("特征与标签行数不一致");
        }
        int classes = 0;
        for (int label : y) {
            classes = Math.max(classes, label + 1);
        }
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < x.length; i++) {
            indices.add(i);
        }
        rootSamples = x.length;
        root = grow(x, y, indices, 0, classes);
        return this;
    }

    /** 单样本预测（走树路径） */
    public int predict(double[] features) {
        if (root == null) {
            throw new IllegalStateException("须先 fit");
        }
        Node node = root;
        while (!node.leaf) {
            node = features[node.feature] <= node.threshold ? node.left : node.right;
        }
        return node.prediction;
    }

    /** 批量预测 */
    public int[] predictAll(double[][] x) {
        int[] out = new int[x.length];
        for (int i = 0; i < x.length; i++) {
            out[i] = predict(x[i]);
        }
        return out;
    }

    /** 特征重要性：Σ(节点样本占比×不纯度减少) 归一（全零则全零） */
    public double[] featureImportances() {
        if (root == null) {
            throw new IllegalStateException("须先 fit");
        }
        int width = maxFeatureWidth(root);
        double[] importance = new double[width];
        if (width > 0) {
            accumulate(root, importance);
            double total = 0.0d;
            for (double v : importance) {
                total += v;
            }
            if (total > 0.0d) {
                for (int i = 0; i < importance.length; i++) {
                    importance[i] /= total;
                }
            }
        }
        return importance;
    }

    private int maxFeatureWidth(Node node) {
        if (node == null || node.leaf) {
            return 0;
        }
        return Math.max(node.feature + 1,
                Math.max(maxFeatureWidth(node.left), maxFeatureWidth(node.right)));
    }

    private void accumulate(Node node, double[] importance) {
        if (node == null || node.leaf) {
            return;
        }
        importance[node.feature] += (node.samples / (double) rootSamples) * node.impurityDecrease;
        accumulate(node.left, importance);
        accumulate(node.right, importance);
    }

    private Node grow(double[][] x, int[] y, List<Integer> indices, int depth, int classes) {
        int[] counts = classCounts(y, indices, classes);
        Node node = new Node();
        node.samples = indices.size();
        if (singleClass(counts) || depth >= maxDepth || indices.size() < 2 * minSamplesLeaf) {
            node.leaf = true;
            node.prediction = argmax(counts);
            return node;
        }
        double parentImpurity = impurity(counts, indices.size());
        double bestGain = 0.0d;
        int bestFeature = -1;
        double bestThreshold = 0.0d;
        List<Integer> bestLeft = null;
        List<Integer> bestRight = null;
        int cols = x[0].length;
        for (int f = 0; f < cols; f++) {
            List<Double> values = new ArrayList<>();
            for (int idx : indices) {
                values.add(x[idx][f]);
            }
            List<Double> distinct = new ArrayList<>(new TreeSet<>(values));
            for (int v = 1; v < distinct.size(); v++) {
                double threshold = (distinct.get(v - 1) + distinct.get(v)) / 2.0d;
                List<Integer> left = new ArrayList<>();
                List<Integer> right = new ArrayList<>();
                for (int idx : indices) {
                    if (x[idx][f] <= threshold) {
                        left.add(idx);
                    } else {
                        right.add(idx);
                    }
                }
                if (left.size() < minSamplesLeaf || right.size() < minSamplesLeaf) {
                    continue;
                }
                double gain = parentImpurity
                        - (left.size() * impurity(classCounts(y, left, classes), left.size())
                        + right.size() * impurity(classCounts(y, right, classes), right.size()))
                        / (double) indices.size();
                if (gain > bestGain) {
                    bestGain = gain;
                    bestFeature = f;
                    bestThreshold = threshold;
                    bestLeft = left;
                    bestRight = right;
                }
            }
        }
        if (bestFeature < 0) {
            node.leaf = true;
            node.prediction = argmax(counts);
            return node;
        }
        node.feature = bestFeature;
        node.threshold = bestThreshold;
        node.impurityDecrease = bestGain;
        node.left = grow(x, y, bestLeft, depth + 1, classes);
        node.right = grow(x, y, bestRight, depth + 1, classes);
        return node;
    }

    private double impurity(int[] counts, int total) {
        double impurity = 0.0d;
        if (criterion == Criterion.GINI) {
            for (int count : counts) {
                double p = count / (double) total;
                impurity += p * (1 - p);
            }
            return impurity;
        }
        for (int count : counts) {
            if (count > 0) {
                double p = count / (double) total;
                impurity -= p * (Math.log(p) / Math.log(2.0d));
            }
        }
        return impurity;
    }

    private int[] classCounts(int[] y, List<Integer> indices, int classes) {
        int[] counts = new int[classes];
        for (int idx : indices) {
            counts[y[idx]]++;
        }
        return counts;
    }

    private boolean singleClass(int[] counts) {
        int nonzero = 0;
        for (int count : counts) {
            if (count > 0) {
                nonzero++;
            }
        }
        return nonzero <= 1;
    }

    private int argmax(int[] counts) {
        int best = 0;
        for (int i = 1; i < counts.length; i++) {
            if (counts[i] > counts[best]) {
                best = i;
            }
        }
        return best;
    }
}
