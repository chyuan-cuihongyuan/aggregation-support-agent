package cn.chyuan.ai.domain.mlkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 类别编码（工单 0543 BM3，sklearn OneHotEncoder/OrdinalEncoder 思想）。
 * 类别字典序稳定编码/独热可逆映射/序数编码/未知类别策略（ERROR 抛出或 ZERO 全零向量）/
 * 编码维度断言。sortedDistinct 借用 StandardScaler 包内工具。
 */
public final class CategoryEncoder {

    /** 未知类别策略 */
    public enum UnknownPolicy {
        ERROR, ZERO
    }

    /** 编码模型（字典序类别表） */
    public record Model(List<String> categories) {
    }

    public Model fit(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("类别样本不得为空");
        }
        return new Model(List.copyOf(new TreeSet<>(values)));
    }

    public int ordinal(Model model, String value, UnknownPolicy policy) {
        int idx = model.categories().indexOf(value);
        if (idx < 0) {
            if (policy == UnknownPolicy.ERROR) {
                throw new IllegalArgumentException("未知类别: " + value);
            }
            return -1;
        }
        return idx;
    }

    /** 独热编码（维度=类别数；未知按策略 ERROR/ZERO） */
    public double[] oneHot(Model model, String value, UnknownPolicy policy) {
        int idx = ordinal(model, value, policy);
        double[] out = new double[model.categories().size()];
        if (idx >= 0) {
            out[idx] = 1.0d;
        } else if (policy == UnknownPolicy.ERROR) {
            throw new IllegalArgumentException("未知类别: " + value);
        }
        return out;
    }

    /** 独热解码（须恰有一个 1，否则拒绝） */
    public String decodeOneHot(Model model, double[] oneHot) {
        if (oneHot.length != model.categories().size()) {
            throw new IllegalArgumentException("独热维度与模型不一致");
        }
        int ones = 0;
        int hit = -1;
        for (int i = 0; i < oneHot.length; i++) {
            if (oneHot[i] == 1.0d) {
                ones++;
                hit = i;
            }
        }
        if (ones != 1) {
            throw new IllegalArgumentException("独热向量须恰有一个 1");
        }
        return model.categories().get(hit);
    }

    /** 序数编码列（未知按策略；UNKNOWN 序为 -1 便于下游识别） */
    public int[] encodeOrdinalColumn(Model model, List<String> values, UnknownPolicy policy) {
        int[] out = new int[values.size()];
        for (int i = 0; i < values.size(); i++) {
            out[i] = ordinal(model, values.get(i), policy);
        }
        return out;
    }

    /** 独热批量编码矩阵（行=样本） */
    public double[][] encodeOneHotMatrix(Model model, List<String> values, UnknownPolicy policy) {
        List<double[]> rows = new ArrayList<>(values.size());
        for (String value : values) {
            rows.add(oneHot(model, value, policy));
        }
        return rows.toArray(new double[0][]);
    }
}
