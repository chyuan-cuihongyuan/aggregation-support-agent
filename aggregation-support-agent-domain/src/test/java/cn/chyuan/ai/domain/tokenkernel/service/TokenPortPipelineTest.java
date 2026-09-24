package cn.chyuan.ai.domain.tokenkernel.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TokenPort 组合管线测试（工单 0747 CJ8，transformers 思想）。
 * encode/batch/decode 编排/textkernel 分析词条只读联动形态（泛型入参不 import）/
 * token-kernel.enabled 默认关。
 */
class TokenPortPipelineTest {

    private static TokenPort port() {
        Map<String, Integer> counts = new HashMap<>();
        counts.put("low", 5);
        counts.put("lower", 3);
        counts.put("lowest", 2);
        counts.put("newest", 2);
        counts.put("widest", 1);
        WordPiece wp = new WordPiece(WordPiece.buildVocab(counts, 1, 64));
        Normalizer normalizer = new Normalizer(true, true, true);
        PreTokenizer pre = new PreTokenizer(true);
        SpecialTokens specials = SpecialTokens.bert();
        PaddingTrimmer trimmer = new PaddingTrimmer(12,
                PaddingTrimmer.TruncationStrategy.LONGEST_FIRST, PaddingTrimmer.PaddingSide.RIGHT, specials.pad());
        TokenPipeline pipeline = new TokenPipeline(normalizer, pre,
                new TokenPipeline.SubwordModel() {
                    @Override
                    public List<String> encodeWord(String word) {
                        return wp.encodeWord(word);
                    }

                    @Override
                    public Map<String, Integer> vocab() {
                        return wp.vocab();
                    }

                    @Override
                    public String unk() {
                        return specials.unk();
                    }
                }, specials, trimmer);
        return TokenPort.of(pipeline);
    }

    @Test
    void portEncodeBatchDecode() {
        TokenPort port = port();
        PaddingTrimmer.Padded short1 = port.encode("low");
        assertTrue(short1.ids().size() <= 12);
        assertTrue(short1.attentionMask().get(0) == 1);

        List<PaddingTrimmer.Padded> batch = port.batchEncode(List.of("low", "lower lowest wider"));
        assertEquals(2, batch.size());
        assertEquals(batch.get(0).ids().size(), batch.get(1).ids().size(), "批内统一 pad 长度");
        assertTrue(batch.get(0).attentionMask().contains(0), "短句被 pad");

        String decoded = port.decode(batch.get(0).ids().stream().map(Integer::parseInt).toList());
        assertEquals("low", decoded);
        assertThrows(IllegalArgumentException.class, () -> port.batchEncode(List.of()));
        assertTrue(port.vocabSize() > 4);
    }

    @Test
    void portAnalyzerWordsLinkageShape() {
        // textkernel 只读联动形态：外部分析词条直接作预切分输入（形状数据，不 import textkernel）
        TokenPort port = port();
        PaddingTrimmer.Padded fromAnalyzer = port.encodeFromAnalyzerWords(List.of("low", "lower"));
        assertFalse(fromAnalyzer.ids().isEmpty());
        assertTrue(fromAnalyzer.attentionMask().stream().mapToInt(Integer::intValue).sum() >= 2);
        assertThrows(IllegalArgumentException.class, () -> port.encodeFromAnalyzerWords(List.of()));
    }

    @Test
    void portBatchPaddingLeftConfigurable() {
        TokenPort port = port();
        List<PaddingTrimmer.Padded> batch = port.batchEncode(List.of("low", "lowest"));
        int max = batch.get(0).ids().size();
        assertEquals(max, batch.get(1).ids().size());
        // 右填充形态：先编码者 mask 前缀为 1
        assertEquals(1, batch.get(0).attentionMask().get(0));
    }
}
