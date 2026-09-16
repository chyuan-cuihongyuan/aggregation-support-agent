package cn.chyuan.ai.domain.vectorkernel.service;

/**
 * 标量量化（工单 0438 BA4，qdrant 标量量化思想）。
 * 每向量 min/max 线性映射 float32→int8（255 级）+ 反量化；
 * 量化后点积距离与浮点距离误差上界断言；常数向量退化保护。纯函数。
 */
public class ScalarQuantizer {

    /** 量化产物 */
    public record Quantized(byte[] codes, float min, float max) {
    }

    private static final float LEVELS = 255f;

    /** 量化：code = round((x-min)/(max-min)*255)，常数向量全 0 码保护 */
    public Quantized quantize(float[] vector) {
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (float value : vector) {
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        byte[] codes = new byte[vector.length];
        float range = max - min;
        for (int i = 0; i < vector.length; i++) {
            codes[i] = range == 0 ? 0 : (byte) Math.round((vector[i] - min) / range * LEVELS);
        }
        return new Quantized(codes, min, max);
    }

    /** 反量化 */
    public float[] dequantize(Quantized quantized) {
        float range = quantized.max() - quantized.min();
        float[] out = new float[quantized.codes().length];
        for (int i = 0; i < out.length; i++) {
            out[i] = quantized.min() + (quantized.codes()[i] & 0xFF) / LEVELS * range;
        }
        return out;
    }

    /** 量化空间点积（int8 码距×码距，反量化尺度在调用方折算） */
    public static double dotCodes(byte[] a, byte[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("维度不一致: " + a.length + " vs " + b.length);
        }
        long sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += (long) (a[i] & 0xFF) * (b[i] & 0xFF);
        }
        return sum;
    }

    /**
     * 量化距离 vs 浮点距离的误差上界：
     * 单维反量化误差 ≤ range/255·(n-1)/n·‖v‖ 归一尺度，返回两距离的相对误差是否在容忍度内。
     */
    public boolean withinErrorBound(float[] a, float[] b, double tolerance) {
        float[] deqA = dequantize(quantize(a));
        float[] deqB = dequantize(quantize(b));
        double floatDist = VectorMath.euclidean(a, b);
        double quantDist = VectorMath.euclidean(deqA, deqB);
        double bound = 2.0 * maxRange(a, b) / LEVELS * a.length;
        if (bound == 0) {
            return true;
        }
        return Math.abs(floatDist - quantDist) <= bound * tolerance + 1e-9;
    }

    private static float maxRange(float[] a, float[] b) {
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int i = 0; i < a.length; i++) {
            min = Math.min(min, Math.min(a[i], b[i]));
            max = Math.max(max, Math.max(a[i], b[i]));
        }
        return max - min;
    }
}
