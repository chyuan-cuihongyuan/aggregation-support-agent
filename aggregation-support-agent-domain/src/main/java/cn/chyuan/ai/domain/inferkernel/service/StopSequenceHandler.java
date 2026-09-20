package cn.chyuan.ai.domain.inferkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 停止序列与截断（工单 0508 BI5）。
 * 多停止串检测（token 序列后缀匹配）/最大 token 数截断/
 * finish_reason 归一（stop=命中停止串 / length=达上限 / none=未终止）。
 */
public class StopSequenceHandler {

    /** 终止原因 */
    public enum FinishReason {
        NONE, STOP, LENGTH
    }

    /** 处理结果：截断后的 token 序列 + 终止原因 */
    public record Generation(int[] tokens, FinishReason reason) {
    }

    private final List<int[]> stopSequences;
    private final int maxTokens;

    public StopSequenceHandler(List<int[]> stopSequences, int maxTokens) {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens 须 > 0");
        }
        this.stopSequences = stopSequences == null ? List.of() : List.copyOf(stopSequences);
        this.maxTokens = maxTokens;
    }

    /** 逐 token 推进：命中停止串（含尾部截除）或达上限即停 */
    public Generation feed(java.util.function.IntSupplier tokenSource) {
        List<Integer> generated = new ArrayList<>();
        while (generated.size() < maxTokens) {
            int token = tokenSource.getAsInt();
            generated.add(token);
            for (int[] stop : stopSequences) {
                if (endsWith(generated, stop)) {
                    int[] kept = generated.subList(0, generated.size() - stop.length)
                            .stream().mapToInt(Integer::intValue).toArray();
                    return new Generation(kept, FinishReason.STOP);
                }
            }
        }
        return new Generation(generated.stream().mapToInt(Integer::intValue).toArray(), FinishReason.LENGTH);
    }

    /** 静态序列判定（完整序列一次性判定） */
    public FinishReason reasonOf(List<Integer> tokens) {
        if (tokens.size() >= maxTokens) {
            return FinishReason.LENGTH;
        }
        for (int[] stop : stopSequences) {
            if (endsWith(tokens, stop)) {
                return FinishReason.STOP;
            }
        }
        return FinishReason.NONE;
    }

    private boolean endsWith(List<Integer> tokens, int[] stop) {
        if (tokens.size() < stop.length) {
            return false;
        }
        for (int i = 0; i < stop.length; i++) {
            if (tokens.get(tokens.size() - stop.length + i) != stop[i]) {
                return false;
            }
        }
        return true;
    }
}
