package cn.chyuan.ai.domain.vectorkernel.service;

/**
 * 向量距离内核（工单 0435 BA1，qdrant/chroma 距离口径）。
 * 余弦/点积/欧氏三度量（枚举可配）+ 零向量余弦保护 + 维度不一致拒绝 + L2 归一化开关
 * （归一后余弦=点积口径验证）。纯函数。
 */
public class VectorMath {

    /** 距离度量 */
    public enum Metric {
        COSINE, DOT, EUCLIDEAN
    }

    private final Metric metric;
    private final boolean normalize;

    public VectorMath(Metric metric, boolean normalize) {
        this.metric = metric;
        this.normalize = normalize;
    }

    /** 相似度（越大越近）；欧氏返回负距离保持"越大越近"口径 */
    public double similarity(float[] a, float[] b) {
        requireSameDimension(a, b);
        float[] x = a;
        float[] y = b;
        if (normalize) {
            x = l2Normalize(a);
            y = l2Normalize(b);
        }
        return switch (metric) {
            case COSINE -> cosine(x, y);
            case DOT -> dot(x, y);
            case EUCLIDEAN -> -euclidean(x, y);
        };
    }

    /** 距离（越小越近） */
    public double distance(float[] a, float[] b) {
        return -similarity(a, b);
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    static double euclidean(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double diff = a[i] - b[i];
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }

    static float[] l2Normalize(float[] vector) {
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        float[] out = new float[vector.length];
        if (norm == 0) {
            System.arraycopy(vector, 0, out, 0, vector.length);
            return out;
        }
        float scale = (float) (1.0 / Math.sqrt(norm));
        for (int i = 0; i < vector.length; i++) {
            out[i] = vector[i] * scale;
        }
        return out;
    }

    static void requireSameDimension(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("维度不一致: " + a.length + " vs " + b.length);
        }
    }
}
