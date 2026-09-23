package cn.chyuan.ai.domain.compresskernel.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 压缩内核域单测（工单 0586-0592 BR1-BR7，zstd 思想）。
 * LZ77 滑窗匹配/前缀编码位级往返/帧格式校验拒绝/预设词典/
 * 解压重叠拷贝往返/级别自适应/统计登记。
 */
class CompressKernelTest {

    @Test
    void lz77MatchesRepetitionAndFallsBackToLiterals() {
        byte[] input = "abcabcabcXYZ".getBytes(StandardCharsets.UTF_8);
        List<Lz77Matcher.Token> tokens = Lz77Matcher.match(input, new byte[0], 4096, 3, 16, false);
        assertTrue(tokens.get(0) instanceof Lz77Matcher.Literal);
        assertTrue(tokens.stream().anyMatch(t -> t instanceof Lz77Matcher.Match),
                "重复串应产生回指匹配");
        int produced = 0;
        for (Lz77Matcher.Token token : tokens) {
            produced += token instanceof Lz77Matcher.Literal ? 1 : ((Lz77Matcher.Match) token).length();
        }
        assertEquals(input.length, produced, "记号产出应精确覆盖输入");

        List<Lz77Matcher.Token> single = Lz77Matcher.match(new byte[]{7}, new byte[0], 4096, 3, 16, false);
        assertEquals(1, single.size());
        assertInstanceOf(Lz77Matcher.Literal.class, single.get(0));
    }

    @Test
    void lz77RejectsIllegalArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> Lz77Matcher.match(null, new byte[0], 4096, 3, 16, false));
        assertThrows(IllegalArgumentException.class,
                () -> Lz77Matcher.match(new byte[1], new byte[0], 0, 3, 16, false));
        assertThrows(IllegalArgumentException.class,
                () -> Lz77Matcher.match(new byte[1], new byte[0], 4096, 2, 16, false));
        byte[] bigDict = new byte[8192];
        assertThrows(IllegalArgumentException.class,
                () -> Lz77Matcher.match(new byte[1], bigDict, 4096, 3, 16, false));
    }

    @Test
    void prefixCodeBuildsDeterministicCanonicalCodes() {
        long[] freqs = new long[PrefixCode.SYMBOLS];
        freqs['a'] = 50;
        freqs['b'] = 30;
        freqs['c'] = 10;
        freqs[PrefixCode.END_OF_BLOCK] = 1;
        int[] lengths = PrefixCode.buildLengths(freqs);
        assertArrayEquals(lengths, PrefixCode.buildLengths(freqs), "平局定序应确定性");
        PrefixCode.CanonicalCode code = PrefixCode.CanonicalCode.of(lengths);
        assertTrue(lengths['a'] <= lengths['c'], "高频符号码长应不大于低频");

        long[] single = new long[PrefixCode.SYMBOLS];
        single[9] = 5;
        assertEquals(1, PrefixCode.buildLengths(single)[9], "单符号码长为 1");

        assertThrows(IllegalArgumentException.class,
                () -> PrefixCode.buildLengths(new long[PrefixCode.SYMBOLS]));
        assertThrows(IllegalArgumentException.class,
                () -> PrefixCode.buildLengths(new long[8]));
    }

    @Test
    void prefixCodeBitLevelRoundtrip() {
        long[] freqs = new long[PrefixCode.SYMBOLS];
        for (int s = 0; s < 40; s++) {
            freqs[s] = 40 - s;
        }
        freqs[PrefixCode.END_OF_BLOCK] = 1;
        int[] lengths = PrefixCode.buildLengths(freqs);
        PrefixCode.CanonicalCode code = PrefixCode.CanonicalCode.of(lengths);
        CompressCodec.BitWriter writer = new CompressCodec.BitWriter();
        int[] symbols = {0, 3, 3, 7, 39, 1, PrefixCode.END_OF_BLOCK};
        for (int symbol : symbols) {
            int value = code.codeOf(symbol);
            for (int i = lengths[symbol] - 1; i >= 0; i--) {
                writer.writeBit((value >> i) & 1);
            }
        }
        CompressCodec.BitReader reader = new CompressCodec.BitReader(writer.toByteArray());
        PrefixCode.CanonicalDecoder decoder = new PrefixCode.CanonicalDecoder(lengths);
        for (int expected : symbols) {
            assertEquals(expected, decoder.decode(reader));
        }
    }

    @Test
    void frameRejectsCorruptionAndBadMagic() {
        byte[] input = "重复内容重复内容重复内容unique-tail".getBytes(StandardCharsets.UTF_8);
        CompressCodec.Compressed compressed = CompressCodec.compress(input, null, 6);
        assertArrayEquals(input, CompressCodec.decompress(compressed.frame(), null), "正常往返");

        byte[] badMagic = compressed.frame().clone();
        badMagic[0] = 0x00;
        assertThrows(IllegalArgumentException.class, () -> CompressCodec.decompress(badMagic, null));

        byte[] badVersion = compressed.frame().clone();
        badVersion[4] = 99;
        assertThrows(IllegalArgumentException.class, () -> CompressCodec.decompress(badVersion, null));

        byte[] corrupted = compressed.frame().clone();
        corrupted[corrupted.length - 1] ^= 0x55;
        assertThrows(IllegalArgumentException.class, () -> CompressCodec.decompress(corrupted, null));

        byte[] flipped = compressed.frame().clone();
        flipped[flipped.length / 2] ^= 0x33;
        assertThrows(IllegalArgumentException.class, () -> CompressCodec.decompress(flipped, null));
    }

    @Test
    void dictionaryEliminatesRepetitionAndGuardsFingerprint() {
        byte[] dict = CompressionDictionary.fromText("copyright 2026 agent-chyuan common preamble: ");
        byte[] input = ("copyright 2026 agent-chyuan common preamble: body-one\n"
                + "copyright 2026 agent-chyuan common preamble: body-two\n"
                + "copyright 2026 agent-chyuan common preamble: body-three\n").getBytes(StandardCharsets.UTF_8);
        CompressCodec.Compressed withDict = CompressCodec.compress(input, dict, 6);
        CompressCodec.Compressed bare = CompressCodec.compress(input, null, 6);
        assertTrue(withDict.compressedBytes() < bare.compressedBytes(), "词典应消除公共前缀");
        assertArrayEquals(input, CompressCodec.decompress(withDict.frame(), dict));

        byte[] otherDict = CompressionDictionary.fromText("different seed");
        assertThrows(IllegalArgumentException.class,
                () -> CompressCodec.decompress(withDict.frame(), otherDict));

        byte[] oversize = new byte[CompressionDictionary.MAX_DICT_BYTES + 1];
        assertThrows(IllegalArgumentException.class, () -> CompressionDictionary.validate(oversize));
        assertNotEquals(0L, CompressionDictionary.fingerprint(dict));
        assertEquals(0L, CompressionDictionary.fingerprint(new byte[0]));
    }

    @Test
    void roundtripHandlesOverlapCopyRandomAndMultiBlock() {
        byte[] run = new byte[70000];
        java.util.Arrays.fill(run, (byte) 'x');
        CompressCodec.Compressed runCompressed = CompressCodec.compress(run, null, 6);
        assertArrayEquals(run, CompressCodec.decompress(runCompressed.frame(), null),
                "重叠拷贝（长度>距离）应正确复制");

        Random random = new Random(20260923L);
        byte[] noise = new byte[30000];
        random.nextBytes(noise);
        CompressCodec.Compressed noiseCompressed = CompressCodec.compress(noise, null, 3);
        assertArrayEquals(noise, CompressCodec.decompress(noiseCompressed.frame(), null));

        byte[] multi = new byte[CompressCodec.BLOCK_SIZE * 2 + 500];
        for (int i = 0; i < multi.length; i++) {
            multi[i] = (byte) ("pattern-" + (i % 64) + ";").getBytes(StandardCharsets.UTF_8)[i % 9];
        }
        CompressCodec.Compressed multiCompressed = CompressCodec.compress(multi, null, 6);
        assertArrayEquals(multi, CompressCodec.decompress(multiCompressed.frame(), null),
                "跨块匹配应往返一致");

        CompressCodec.Compressed empty = CompressCodec.compress(new byte[0], null, 1);
        assertEquals(0, CompressCodec.decompress(empty.frame(), null).length);

        byte[] mixed = new byte[CompressCodec.BLOCK_SIZE + 10];
        new Random(7L).nextBytes(mixed);
        for (int i = CompressCodec.BLOCK_SIZE - 20; i < CompressCodec.BLOCK_SIZE + 10; i++) {
            mixed[i] = mixed[i - CompressCodec.BLOCK_SIZE + 40];
        }
        assertArrayEquals(mixed,
                CompressCodec.decompress(CompressCodec.compress(mixed, null, 6).frame(), null));
    }

    @Test
    void levelsAreMonotonicAndAdaptiveSuggestsByRepetition() {
        int lastWindow = 0;
        int lastChain = 0;
        for (int level = CompressionLevels.MIN_LEVEL; level <= CompressionLevels.MAX_LEVEL; level++) {
            CompressionLevels.Params params = CompressionLevels.of(level);
            assertTrue(params.windowSize() >= lastWindow, "窗口应随级别单调不降");
            assertTrue(params.maxChain() >= lastChain, "链深应随级别单调不降");
            lastWindow = params.windowSize();
            lastChain = params.maxChain();
        }
        assertThrows(IllegalArgumentException.class, () -> CompressionLevels.of(0));
        assertThrows(IllegalArgumentException.class, () -> CompressionLevels.of(10));

        byte[] repetitive = new byte[4096];
        java.util.Arrays.fill(repetitive, (byte) 'r');
        assertTrue(CompressionLevels.suggestLevel(repetitive) >= 6, "高重复应建议高档");
        byte[] noise = new byte[4096];
        new Random(42L).nextBytes(noise);
        assertTrue(CompressionLevels.suggestLevel(noise) <= 3, "低重复应建议低档");
        assertTrue(CompressionLevels.repeatRatio(noise) < CompressionLevels.repeatRatio(repetitive));
    }

    @Test
    void statRegistryRecordsAndRanks() {
        CompressStatRegistry registry = new CompressStatRegistry();
        registry.record("scene-a", 6, 1000, 400, 12L, 100L, "");
        registry.record("scene-a", 1, 1000, 900, 8L, 101L, "");
        registry.record("scene-b", 9, 2000, 300, 5L, 102L, "ADAPTIVE");
        assertEquals(3, registry.size());
        assertEquals(400, registry.all().get(0).compressedBytes());
        assertEquals("scene-b", registry.bestRatio(1).get(0).scene());
        assertEquals(0.65d, registry.avgRatio("scene-a"), 1.0E-9);
        assertTrue(Double.isNaN(registry.avgRatio("missing")));
        assertThrows(IllegalArgumentException.class, () -> registry.record(" ", 1, 1, 1, 1L, 1L, ""));
        assertThrows(IllegalArgumentException.class, () -> registry.record("s", 11, 1, 1, 1L, 1L, ""));
        assertThrows(IllegalArgumentException.class, () -> registry.record("s", 1, -1, 1, 1L, 1L, ""));
    }
}
