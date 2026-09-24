package cn.chyuan.ai.domain.tokenkernel.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分词内核测试（工单 0740-0746 CJ1-CJ7，transformers 思想）。
 * 归一化链/预切分偏移/BPE 训练/WordPiece/特殊令牌模板/截断填充/编码管线往返。
 */
class TokenKernelTest {

    private static Map<String, Integer> corpus() {
        Map<String, Integer> counts = new HashMap<>();
        counts.put("low", 5);
        counts.put("lower", 3);
        counts.put("lowest", 2);
        counts.put("newest", 2);
        counts.put("widest", 1);
        return counts;
    }

    @Test
    void normalizerChainOrderDeterministic() {
        Normalizer full = new Normalizer(true, true, true);
        assertEquals("abc123", full.normalize("ＡＢＣ１２３"), "全角转半角再小写");
        assertEquals("a b", full.normalize("  a \t b  "), "空白折叠");
        assertEquals("ａｂ", Normalizer.off().normalize("ａｂ"), "关链原样");
        Normalizer lowerOnly = new Normalizer(true, false, false);
        assertEquals("ab", lowerOnly.normalize("AB"));
        assertThrows(IllegalArgumentException.class, () -> full.normalize(null));
    }

    @Test
    void preTokenizerWhitespacePunctuationOffsets() {
        PreTokenizer pre = new PreTokenizer(true);
        List<PreTokenizer.Span> spans = pre.pretokenize("hello, world");
        assertEquals(List.of("hello", ",", "world"), spans.stream().map(PreTokenizer.Span::text).toList());
        assertEquals(0, spans.get(0).start());
        assertEquals(5, spans.get(0).end());
        assertEquals(5, spans.get(1).start(), "标点单字符片带偏移");

        PreTokenizer keep = new PreTokenizer(false);
        assertEquals(List.of("don't"), keep.pretokenize("don't").stream().map(PreTokenizer.Span::text).toList());
        assertTrue(pre.pretokenize("   ").isEmpty(), "空段丢弃");
        assertThrows(IllegalArgumentException.class, () -> pre.pretokenize(null));
    }

    @Test
    void bpeTrainerLearnsMergesDeterministic() {
        BpeTrainer.Model model = BpeTrainer.train(BpeTrainer.checkCorpus(corpus()), 24);
        assertFalse(model.merges().isEmpty(), "学到合并");
        assertTrue(model.vocab().size() <= 24 + 2, "词表上限（含特殊与字符）");
        // 最频繁 pair（l+o）应被合并成 lo
        boolean hasLo = model.merges().stream().anyMatch(m -> (m[0] + m[1]).equals("lo"));
        assertTrue(hasLo, "最高频 pair l+o 首个合并");
        List<String> lowParts = model.encodeWord("low");
        assertEquals("low", String.join("", lowParts), "编码保序拼接");
        assertTrue(model.id("low") >= 0 || model.encodeWord("low").size() > 0);

        BpeTrainer.Model again = BpeTrainer.train(corpus(), 24);
        assertEquals(model.merges().stream().map(m -> m[0] + "+" + m[1]).toList(),
                again.merges().stream().map(m -> m[0] + "+" + m[1]).toList(), "训练确定性（平票字典序）");
        assertThrows(IllegalArgumentException.class, () -> BpeTrainer.train(new HashMap<>(), 30));
        assertThrows(IllegalArgumentException.class, () -> BpeTrainer.train(corpus(), 10));
        assertThrows(IllegalArgumentException.class, () -> BpeTrainer.checkCorpus(Map.of("x", 0)));
    }

    @Test
    void wordPieceGreedyLongestWithUnk() {
        Map<String, Integer> vocab = WordPiece.buildVocab(corpus(), 1, 64);
        WordPiece wp = new WordPiece(vocab);
        List<String> parts = wp.encodeWord("lower");
        assertEquals("lower", String.join("", parts.stream().map(p -> p.startsWith("##") ? p.substring(2) : p).toList()),
                "贪心覆盖整词");
        assertTrue(wp.encodeWord("zzz").contains(WordPiece.UNK), "UNK 兜底");
        assertNull(wp.id("[NOPE]"));
        assertThrows(IllegalArgumentException.class, () -> new WordPiece(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> wp.encodeWord(""));
    }

    @Test
    void specialTokensTemplatesAndSplitProtect() {
        SpecialTokens specials = SpecialTokens.bert();
        List<String> single = specials.templateSingle(List.of("hello", "world"));
        assertEquals(List.of("[CLS]", "hello", "world", "[SEP]"), single);
        List<String> pair = specials.templatePair(List.of("a"), List.of("b"));
        assertEquals(List.of("[CLS]", "a", "[SEP]", "b", "[SEP]"), pair);

        List<String> segments = specials.splitProtect("前[CLS]中[SEP]尾");
        assertEquals(List.of("前", "[CLS]", "中", "[SEP]", "尾"), segments, "特殊令牌整段优先");
        assertTrue(specials.isSpecial("[PAD]"));
        assertFalse(specials.isSpecial("[CLS]x"));
        assertThrows(IllegalArgumentException.class, () -> specials.splitProtect(null));
    }

    @Test
    void truncationAndPaddingMasks() {
        PaddingTrimmer trimmer = new PaddingTrimmer(6,
                PaddingTrimmer.TruncationStrategy.LONGEST_FIRST, PaddingTrimmer.PaddingSide.RIGHT, "[PAD]");
        List<String> long1 = List.of("a", "b", "c", "d", "e");
        List<String> long2 = List.of("x", "y", "z", "w", "v", "u", "t");
        List<List<String>> pair = trimmer.truncatePair(long1, long2);
        assertEquals(6, pair.get(0).size() + pair.get(1).size(), "longest_first 交替截至上限");
        assertEquals(3, pair.get(0).size());
        assertEquals(3, pair.get(1).size());

        PaddingTrimmer onlyFirst = new PaddingTrimmer(5,
                PaddingTrimmer.TruncationStrategy.ONLY_FIRST, PaddingTrimmer.PaddingSide.RIGHT, "[PAD]");
        List<List<String>> of = onlyFirst.truncatePair(List.of("a", "b", "c", "d"), List.of("x", "y"));
        assertEquals(3, of.get(0).size(), "only_first 只截第一句");
        assertEquals(2, of.get(1).size());
        assertThrows(IllegalArgumentException.class, () -> onlyFirst.truncatePair(List.of("a"), List.of("x", "y", "z", "z2", "z3", "z4", "z5")));

        PaddingTrimmer.Padded padded = trimmer.pad(List.of("a", "b"), "[PAD]", null);
        assertEquals(List.of("a", "b", "[PAD]", "[PAD]", "[PAD]", "[PAD]"), padded.ids());
        assertEquals(List.of(1, 1, 0, 0, 0, 0), padded.attentionMask());

        PaddingTrimmer left = new PaddingTrimmer(4,
                PaddingTrimmer.TruncationStrategy.LONGEST_FIRST, PaddingTrimmer.PaddingSide.LEFT, "[PAD]");
        PaddingTrimmer.Padded leftPadded = left.pad(List.of("a", "b"), "[PAD]", null);
        assertEquals(List.of("[PAD]", "[PAD]", "a", "b"), leftPadded.ids());
        assertEquals(List.of(0, 0, 1, 1), leftPadded.attentionMask());
        assertThrows(IllegalArgumentException.class, () -> trimmer.pad(List.of("a", "b", "c", "d", "e", "f", "g"), "[PAD]", null));
        assertThrows(IllegalArgumentException.class, () -> new PaddingTrimmer(0, null, null, null));
    }

    @Test
    void pipelineEncodeDecodeRoundTrip() {
        Map<String, Integer> vocab = WordPiece.buildVocab(corpus(), 1, 64);
        WordPiece wp = new WordPiece(vocab);
        Normalizer normalizer = new Normalizer(true, true, true);
        PreTokenizer pre = new PreTokenizer(true);
        SpecialTokens specials = SpecialTokens.bert();
        PaddingTrimmer trimmer = new PaddingTrimmer(16,
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

        PaddingTrimmer.Padded result = pipeline.encode("lowest WIDEST", true);
        assertTrue(result.ids().size() <= 16);
        assertTrue(result.attentionMask().contains(1));
        assertEquals(result.ids().size(), result.attentionMask().size());

        List<Integer> ids = result.ids().stream().map(Integer::parseInt).toList();
        String decoded = pipeline.decode(ids);
        assertEquals("lowest widest", decoded, "小写归一后词级往返（特殊令牌跳过 ## 拼接）");
        assertThrows(IllegalArgumentException.class, () -> pipeline.idToToken(9999));
        assertThrows(IllegalArgumentException.class, () -> pipeline.tokenToId("[NOPE]"));
    }
}
