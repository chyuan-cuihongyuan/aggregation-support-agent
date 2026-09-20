package cn.chyuan.ai.domain.inferkernel.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 推理采样内核 BI1-BI7 单测（工单 0504-0510）：
 * BPE/logits 基元/top-k top-p/重复惩罚/停止截断/束搜索/KV 缓存。
 */
class InferKernelTest {

    private BpeTokenizer smallTokenizer() {
        Map<String, Integer> vocab = new HashMap<>();
        String symbols = "abcd";
        int id = 0;
        for (char c : symbols.toCharArray()) {
            vocab.put(String.valueOf(c), id++);
        }
        vocab.put("ab", id++);
        vocab.put("cd", id++);
        vocab.put("abcd", id++);
        Map<String, Integer> ranks = new HashMap<>();
        ranks.put("a\u0000b", 0);
        ranks.put("c\u0000d", 1);
        ranks.put("ab\u0000cd", 2);
        return new BpeTokenizer(vocab, ranks);
    }

    @Test
    void BI1_BPE合并往返与字节回退() {
        BpeTokenizer tokenizer = smallTokenizer();
        List<BpeTokenizer.Token> tokens = tokenizer.encode("abcd");
        assertEquals(List.of("abcd"), tokens.stream().map(BpeTokenizer.Token::text).toList(),
                "合并规则全链应用");
        assertEquals(tokenizer.vocabSize() - 1, tokens.get(0).id());
        assertEquals("abcd", tokenizer.decode(tokens), "解码往返等价");
        List<BpeTokenizer.Token> bytes = tokenizer.encode("Z");
        assertEquals(1, bytes.size());
        assertTrue(bytes.get(0).text().startsWith("<0x"), "未知字符字节回退");
        assertEquals(-1, bytes.get(0).id(), "字节 token 词表外 id=-1");
    }

    @Test
    void BI2_softmax温度与禁用屏蔽() {
        double[] logits = {1.0, 2.0, 3.0};
        double[] probs = LogitsOps.softmax(logits);
        assertEquals(1.0, LogitsOps.sum(probs), 1e-9, "softmax 归一");
        assertTrue(probs[2] > probs[0]);
        double[] hot = LogitsOps.softmax(LogitsOps.applyTemperature(logits, 100));
        assertTrue(hot[2] < probs[2], "高温压平分布");
        double[] greedy = LogitsOps.applyTemperature(logits, 0);
        assertEquals(1.0, greedy[2], 1e-9, "T=0 收敛 argmax one-hot");
        double[] banned = LogitsOps.softmax(LogitsOps.applyBias(logits, java.util.Set.of(2)));
        assertTrue(banned[2] < 1e-10, "禁用 token 概率≈0");
        assertThrows(ArrayIndexOutOfBoundsException.class,
                () -> LogitsOps.applyBias(logits, java.util.Set.of(9)));
    }

    @Test
    void BI3_topK_topP截断与确定性() {
        double[] probs = LogitsOps.softmax(new double[]{3.0, 2.0, 1.0, 0.5});
        Sampler sampler = new Sampler(new java.util.Random(42)::nextDouble);
        List<Sampler.Candidate> top2 = sampler.topK(probs, 2);
        assertEquals(2, top2.size());
        assertEquals(1.0, top2.stream().mapToDouble(Sampler.Candidate::probability).sum(), 1e-9, "重归一化");
        assertEquals(0, top2.get(0).tokenId(), "概率最高在前");
        List<Sampler.Candidate> nucleus = sampler.topP(top2, 0.99);
        assertTrue(nucleus.size() <= 2 && nucleus.size() >= 1, "top-p 至少保留 1 个");
        assertEquals(1.0, nucleus.stream().mapToDouble(Sampler.Candidate::probability).sum(), 1e-9);
        Sampler fixed = new Sampler(() -> 0.0);
        assertEquals(top2.get(0).tokenId(), fixed.sample(top2), "固定随机端口取首候选");
        assertThrows(IllegalArgumentException.class, () -> sampler.topP(top2, 0.0));
        assertThrows(IllegalArgumentException.class, () -> sampler.topK(probs, 0));
    }

    @Test
    void BI4_重复惩罚概率下降() {
        double[] logits = {2.0, 1.0, 1.0};
        double[] before = LogitsOps.softmax(logits);
        RepetitionPenalty penalty = new RepetitionPenalty(0.5, 0.2, 1.2, 4);
        double[] after = LogitsOps.softmax(penalty.apply(logits, List.of(0, 0)));
        assertTrue(after[0] < before[0], "受罚 token 概率下降");
        assertTrue(after[1] > before[1], "未受罚 token 概率相对上升");
        double[] outside = penalty.apply(logits, List.of(1));
        assertEquals(2.0, outside[0], 1e-9, "窗口外 token 的 logit 不变");
    }

    @Test
    void BI5_停止序列与长度截断() {
        StopSequenceHandler handler = new StopSequenceHandler(List.of(new int[]{7, 8}), 10);
        java.util.PrimitiveIterator.OfInt stopRun = java.util.Arrays.stream(new int[]{1, 2, 7, 8, 9, 3})
                .iterator();
        StopSequenceHandler.Generation stopped = handler.feed(stopRun::nextInt);
        assertEquals(StopSequenceHandler.FinishReason.STOP, stopped.reason());
        assertEquals(2, stopped.tokens().length, "停止串尾部截除");
        java.util.PrimitiveIterator.OfInt longRun = java.util.stream.IntStream.range(0, 15)
                .map(i -> i % 5).iterator();
        StopSequenceHandler.Generation length = handler.feed(longRun::nextInt);
        assertEquals(StopSequenceHandler.FinishReason.LENGTH, length.reason(), "达上限截断");
        assertEquals(10, length.tokens().length);
        assertEquals(StopSequenceHandler.FinishReason.NONE, handler.reasonOf(List.of(1, 2)));
    }

    @Test
    void BI6_贪心束搜索与长度惩罚() {
        List<double[]> steps = List.of(
                new double[]{-0.1, -0.5, -2.0},
                new double[]{-0.2, -0.1, -3.0});
        assertEquals(List.of(0, 1), Decoder.greedy(steps), "greedy argmax 序列");
        List<Decoder.Sequence> beams = Decoder.beam(steps, 2);
        assertEquals(2, beams.size(), "束宽保留");
        assertEquals(List.of(0, 1), beams.get(0).tokens, "联合最优束在前（确定性）");
        Decoder.Sequence flat = beams.get(0);
        assertEquals(Decoder.normalizedScore(flat, 0), flat.cumulativeLogProb, 1e-9, "α=0 不惩罚");
        assertEquals(Decoder.normalizedScore(flat, 1), flat.cumulativeLogProb / 2, 1e-9, "α=1 平均");
        List<Decoder.Sequence> again = Decoder.beam(steps, 2);
        assertEquals(beams, again, "同输入确定性输出");
        assertThrows(IllegalArgumentException.class, () -> Decoder.beam(steps, 0));
    }

    @Test
    void BI7_KV缓存命中复用与LRU() {
        PrefixKvCache cache = new PrefixKvCache(2);
        cache.put(List.of(1, 2, 3), 128);
        assertEquals(3, cache.lookup(List.of(1, 2, 3, 4)), "最长前缀命中复用");
        assertEquals(0, cache.lookup(List.of(9, 9)), "未命中");
        cache.put(List.of(4, 5), 64);
        PrefixKvCache.Stats afterTwo = cache.stats();
        assertEquals(2, afterTwo.entries(), "容量 2 满");
        cache.put(List.of(6, 7), 32);
        PrefixKvCache.Stats evicted = cache.stats();
        assertEquals(1, evicted.evictions(), "超容量 LRU 逐出");
        assertEquals(2, evicted.entries());
        assertTrue(cache.lookup(List.of(1, 2, 3)) == 0, "最久未用条目已被逐出");
        cache.put(List.of(4, 5), 64);
        assertTrue(cache.invalidate(List.of(4, 5)));
        assertFalse(cache.invalidate(List.of(4, 5)), "重复失效幂等");
    }
}
