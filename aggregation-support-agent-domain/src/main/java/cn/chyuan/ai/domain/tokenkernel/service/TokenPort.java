package cn.chyuan.ai.domain.tokenkernel.service;

import java.util.List;

/**
 * 分词端口（工单 0747 CJ8，transformers 思想）。
 * encode/batch/decode 入口统一编排/与 textkernel 只读联动（AnalyzerChain 词条作预切分输入形态）/
 * token-kernel.enabled 默认关（开启才改变行为）。
 */
public interface TokenPort {

    /** 单文本编码（返回 id 序列与 mask） */
    PaddingTrimmer.Padded encode(String text);

    /** 批量编码：统一 pad 到批内最长 */
    List<PaddingTrimmer.Padded> batchEncode(List<String> texts);

    /** 解码 */
    String decode(List<Integer> ids);

    /** textkernel 只读联动形态：外部分析词条直接作预切分输入（跳过内置预切分，形状数据不 import textkernel） */
    PaddingTrimmer.Padded encodeFromAnalyzerWords(List<String> analyzerWords);

    /** 词表规模 */
    int vocabSize();

    static TokenPort of(TokenPipeline pipeline) {
        return new InMemoryToken(pipeline);
    }
}

final class InMemoryToken implements TokenPort {

    private final TokenPipeline pipeline;

    InMemoryToken(TokenPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    public PaddingTrimmer.Padded encode(String text) {
        return pipeline.encode(text, true);
    }

    @Override
    public List<PaddingTrimmer.Padded> batchEncode(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("空批");
        }
        List<PaddingTrimmer.Padded> encoded = new java.util.ArrayList<>();
        int longest = 0;
        for (String text : texts) {
            PaddingTrimmer.Padded p = pipeline.encode(text, true);
            longest = Math.max(longest, p.ids().size());
            encoded.add(p);
        }
        List<PaddingTrimmer.Padded> out = new java.util.ArrayList<>();
        for (PaddingTrimmer.Padded p : encoded) {
            out.add(p.ids().size() == longest ? p : pipeline.repad(p, longest));
        }
        return out;
    }

    @Override
    public String decode(List<Integer> ids) {
        return pipeline.decode(ids);
    }

    @Override
    public PaddingTrimmer.Padded encodeFromAnalyzerWords(List<String> analyzerWords) {
        if (analyzerWords == null || analyzerWords.isEmpty()) {
            throw new IllegalArgumentException("词条空");
        }
        List<String> tokens = new java.util.ArrayList<>();
        for (String word : analyzerWords) {
            tokens.addAll(pipeline.model().encodeWord(word));
        }
        List<String> resolved = new java.util.ArrayList<>();
        for (String token : tokens) {
            resolved.add(pipeline.model().vocab().containsKey(token) ? token : pipeline.unkToken());
        }
        return pipeline.repadTokens(resolved);
    }

    @Override
    public int vocabSize() {
        return pipeline.model().vocab().size();
    }
}
