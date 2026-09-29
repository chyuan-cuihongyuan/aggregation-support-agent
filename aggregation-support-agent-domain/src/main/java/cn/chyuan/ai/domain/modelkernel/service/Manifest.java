package cn.chyuan.ai.domain.modelkernel.service;

import java.util.List;
import java.util.Map;

/**
 * manifest 分层解析（工单 1029 EL1，ollama 思想）。
 * 分层 mediatype·digest·size 解析/缺字段拒绝/空层拒绝。
 */
public final class Manifest {

    /** 清单层：媒体类型 + 内容寻址 digest + 字节数 */
    public record Layer(String mediaType, String digest, long size) {

        public Layer {
            if (mediaType == null || mediaType.isEmpty()) {
                throw new IllegalArgumentException("层缺 mediaType");
            }
            if (digest == null || digest.isEmpty()) {
                throw new IllegalArgumentException("层缺 digest");
            }
            if (size < 0) {
                throw new IllegalArgumentException("层 size 为负: " + size);
            }
        }
    }

    private final List<Layer> layers;
    private final long totalSize;

    private Manifest(List<Layer> layers) {
        this.layers = List.copyOf(layers);
        this.totalSize = layers.stream().mapToLong(Layer::size).sum();
    }

    /** 解析层清单：空层拒绝 */
    public static Manifest parse(List<Map<String, Object>> rawLayers) {
        if (rawLayers == null || rawLayers.isEmpty()) {
            throw new IllegalArgumentException("层清单为空");
        }
        List<Layer> layers = rawLayers.stream().map(raw -> new Layer(
                requireText(raw, "mediaType"),
                requireText(raw, "digest"),
                requireSize(raw))).toList();
        return new Manifest(layers);
    }

    private static String requireText(Map<String, Object> raw, String field) {
        Object value = raw.get(field);
        if (value == null || String.valueOf(value).isEmpty()) {
            throw new IllegalArgumentException("层缺字段: " + field);
        }
        return String.valueOf(value);
    }

    private static long requireSize(Map<String, Object> raw) {
        Object value = raw.get("size");
        if (value == null) {
            throw new IllegalArgumentException("层缺字段: size");
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("size 非数字: " + value);
        }
        return number.longValue();
    }

    public List<Layer> layers() {
        return layers;
    }

    public long totalSize() {
        return totalSize;
    }

    public int layerCount() {
        return layers.size();
    }
}
