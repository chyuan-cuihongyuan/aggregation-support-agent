package cn.chyuan.ai.domain.compresskernel.service;

import cn.chyuan.ai.domain.storekernel.service.SstSegment;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 压缩端口组合管线测试（工单 0593 BR8）。
 * compress-kernel.enabled 默认关（开启才改变行为）；
 * 与 storekernel 只读联动：SstSegment 段行序列化字节可选压缩形态。
 */
class CompressPortPipelineTest {

    @Test
    void pipelineCompressDecompressAndRegistersStats() {
        CompressPort port = new CompressPort.InMemoryCompressor();
        byte[] input = repeat("聚合压缩管线样本-", 400).getBytes(StandardCharsets.UTF_8);
        CompressPort.Outcome outcome = port.compress("rag-chunk", input, 6);
        assertEquals(6, outcome.level());
        assertArrayEquals(input, port.decompress(outcome.frame(), null));
        assertTrue(outcome.ratio() < 0.5d, "重复样本压缩比应显著小于 1");
        assertEquals(1, port.registry().size());
        assertEquals("rag-chunk", port.registry().all().get(0).scene());
    }

    @Test
    void adaptivePipelineChoosesLevelByContent() {
        CompressPort port = new CompressPort.InMemoryCompressor();
        byte[] repetitive = new byte[8192];
        java.util.Arrays.fill(repetitive, (byte) 'z');
        CompressPort.Outcome hot = port.compressAdaptive("hot", repetitive);
        assertTrue(hot.level() >= 6);

        byte[] noise = new byte[8192];
        new java.util.Random(9L).nextBytes(noise);
        CompressPort.Outcome cold = port.compressAdaptive("cold", noise);
        assertTrue(cold.level() <= 3);
        assertThrows(IllegalArgumentException.class, () -> port.compress(null, noise, 3));
    }

    @Test
    void dictionaryPipelineRoundsAndRejectsMismatch() {
        CompressPort port = new CompressPort.InMemoryCompressor();
        byte[] dict = CompressionDictionary.fromText("模板公共头部 template-common-header;");
        byte[] input = ("模板公共头部 template-common-header;A\n"
                + "模板公共头部 template-common-header;B\n").getBytes(StandardCharsets.UTF_8);
        CompressPort.Outcome outcome = port.compressWithDictionary("prompt", input, dict, 6);
        assertArrayEquals(input, port.decompress(outcome.frame(), dict));
        assertThrows(IllegalArgumentException.class,
                () -> port.decompress(outcome.frame(), CompressionDictionary.fromText("other")));
    }

    @Test
    void storeKernelSegmentRowsCompressAsOptionalShape() {
        List<SstSegment.Row> rowList = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            rowList.add(new SstSegment.Row("key-" + String.format("%04d", i), 0,
                    "value-payload-" + i + "-with-repetition-tail"));
        }
        SstSegment segment = SstSegment.fromRows(1, 0, rowList);
        List<String> rows = new ArrayList<>();
        for (SstSegment.Row row : segment.rows()) {
            rows.add(row.key() + "\u0000" + row.sequence() + "\u0000" + row.value());
        }
        CompressPort port = new CompressPort.InMemoryCompressor();
        CompressPort.Outcome outcome = port.compressSegmentRows("sst-segment", rows, 6);
        byte[] roundtrip = port.decompress(outcome.frame(), null);
        String joined = new String(roundtrip, StandardCharsets.UTF_8);
        assertTrue(joined.contains("key-0000\u00000\u0000value-payload-0-with-repetition-tail"));
        assertTrue(joined.contains("key-0199\u00000\u0000value-payload-199-with-repetition-tail"));
        assertEquals(rows.size(), joined.split("\n").length);
        assertEquals("SST", port.registry().all().get(0).remark());
    }

    @Test
    void bigInputMultiBlockThroughPort() {
        CompressPort port = new CompressPort.InMemoryCompressor();
        byte[] input = repeat("多块跨块匹配样本内容", 12000).getBytes(StandardCharsets.UTF_8);
        CompressPort.Outcome outcome = port.compress("big", input, 4);
        assertTrue(input.length > CompressCodec.BLOCK_SIZE, "输入应跨多块");
        assertArrayEquals(input, port.decompress(outcome.frame(), null));
        Map<String, Long> byScene = new LinkedHashMap<>();
        for (CompressStatRegistry.Stat stat : port.registry().all()) {
            byScene.merge(stat.scene(), 1L, Long::sum);
        }
        assertEquals(1L, byScene.get("big"));
    }

    private static String repeat(String unit, int times) {
        StringBuilder sb = new StringBuilder(unit.length() * times);
        for (int i = 0; i < times; i++) {
            sb.append(unit);
        }
        return sb.toString();
    }
}
