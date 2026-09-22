package cn.chyuan.ai.domain.segkernel.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分词端口组合管线 BK8 单测（工单 0532）：
 * 装载词典→分词→高频词条登记→textkernel 分析输入只读联动/seven 开关默认关语义。
 */
class SegPortPipelineTest {

    private SegPort.InMemorySegmenter segmenter() {
        SegPort.InMemorySegmenter segmenter = new SegPort.InMemorySegmenter();
        Map<String, Long> dict = new LinkedHashMap<>();
        dict.put("研究", 1000L);
        dict.put("研究生", 500L);
        dict.put("生命", 800L);
        dict.put("起源", 600L);
        dict.put("学习", 700L);
        segmenter.loadDict(dict);
        return segmenter;
    }

    @Test
    void BK8_组合管线_词典装载到分词到词条登记() {
        SegPort.InMemorySegmenter segmenter = segmenter();
        assertEquals(List.of("研究", "生命", "起源"), segmenter.segment("研究生命起源"));
        segmenter.registerTopTerms();
        assertTrue(segmenter.registry().contains("研究"), "切分词登记进词条表");
        assertEquals(SegTermRegistry.SOURCE_DICT, segmenter.registry().topByFreq(10).get(0).source());
    }

    @Test
    void BK8_用户词与屏蔽词走端口生效() {
        SegPort.InMemorySegmenter segmenter = segmenter();
        segmenter.addUserWord("研究生命", 900L);
        assertEquals(List.of("研究生命"), segmenter.segment("研究生命"), "用户词整词胜出");
        SegPort.InMemorySegmenter blocker = segmenter();
        blocker.blockWord("研究");
        assertEquals(List.of("研", "究", "生"), blocker.segment("研究生"), "屏蔽词强制切开");
    }

    @Test
    void BK8_关键词与停用词分析联动() {
        SegPort.InMemorySegmenter segmenter = segmenter();
        segmenter.addStopword("研究");
        List<String> tokens = segmenter.toAnalyzerInput("研究生命起源");
        assertEquals(List.of("生命", "起源"), tokens, "词级 token 流去停用词（textkernel 分析输入形态）");
        assertEquals(List.of("研究", "生命"), segmenter.segment("研究生命"), "切分不含停用词过滤");
        List<String> keywords = segmenter.keywords("研究生命起源", 2);
        assertEquals(2, keywords.size());
        assertTrue(keywords.contains("研究") || keywords.contains("生命"));
    }

    @Test
    void BK8_未切分先登记拒绝与null词典拒绝() {
        SegPort.InMemorySegmenter segmenter = segmenter();
        assertThrows(IllegalStateException.class, segmenter::registerTopTerms, "须先 segment");
        assertThrows(IllegalArgumentException.class, () -> segmenter.loadDict(null), "null 词典拒绝");
    }
}
