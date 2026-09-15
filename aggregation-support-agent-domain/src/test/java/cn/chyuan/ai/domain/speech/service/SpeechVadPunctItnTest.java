package cn.chyuan.ai.domain.speech.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AU1-AU3 单测（工单 0379/0380/0381）：VAD 滞回切分/标点恢复/ITN 归一。
 */
class SpeechVadPunctItnTest {

    @Test
    void VAD滞回双门限切分与抖动不抖段() {
        // 高门限 0.6 低门限 0.3；信号带抖动（0.4-0.5 徘徊不出段）
        VoiceActivityDetector detector = new VoiceActivityDetector(0.6, 0.3, 2, 2);
        double[] energy = {0.1, 0.7, 0.8, 0.45, 0.5, 0.42, 0.75, 0.2, 0.65, 0.65, 0.1, 0.1};
        List<VoiceActivityDetector.Segment> segments = detector.detect(energy);
        // 抖动段 [1,7) 与 [8,10) 间隙 1 帧 < 最小间隙 2 → 合并
        assertEquals(1, segments.size());
        assertEquals(1, segments.get(0).startFrame());
        assertEquals(10, segments.get(0).endFrame());
    }

    @Test
    void VAD最短语音段丢弃与空输入() {
        VoiceActivityDetector detector = new VoiceActivityDetector(0.6, 0.3, 3, 1);
        // [1,3) 仅 2 帧 → 丢弃
        double[] energy = {0.1, 0.8, 0.8, 0.1, 0.1, 0.8, 0.8, 0.8, 0.1};
        List<VoiceActivityDetector.Segment> segments = detector.detect(energy);
        assertEquals(1, segments.size());
        assertEquals(5, segments.get(0).startFrame());
        assertEquals(8, segments.get(0).endFrame());
        assertTrue(new VoiceActivityDetector(0.6, 0.3, 1, 1).detect(new double[0]).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new VoiceActivityDetector(0.3, 0.6, 1, 1));
    }

    @Test
    void 标点恢复停顿疑问词句长与幂等() {
        PunctuationRestorer restorer = PunctuationRestorer.defaults();
        String text = restorer.restore(List.of(
                new PunctuationRestorer.Word("今天天气", 700L),
                new PunctuationRestorer.Word("很好", 300L),
                new PunctuationRestorer.Word("你要出门", 100L),
                new PunctuationRestorer.Word("吗", 0L)));
        assertEquals("今天天气。很好，你要出门吗？", text);
        // 幂等：已带标点不再插（单词直接返回）
        assertEquals("好的。", restorer.restore(List.of(new PunctuationRestorer.Word("好的。", 0L))));
        // 句长上限强制断句
        PunctuationRestorer strict = new PunctuationRestorer(600L, 250L, 3, java.util.Set.of());
        assertEquals("一二三。四五六。", strict.restore(List.of(
                new PunctuationRestorer.Word("一二三", 0L),
                new PunctuationRestorer.Word("四五六", 0L))));
    }

    @Test
    void ITN数字日期单位规则与透传() {
        InverseTextNormalizer itn = InverseTextNormalizer.defaults();
        assertEquals("完成123米测试", itn.normalize("完成一百二十三米测试"));
        assertEquals("达标50.5%，共60000", itn.normalize("达标百分之五十点五，共六万"));
        assertEquals("9月15日 开会", itn.normalize("九月十五日 开会"));
        // 无规则命中原样透传
        assertEquals("hello world", itn.normalize("hello world"));
        // 规则优先级：百分比先于裸数字（避免只转一半）
        assertEquals("25%", itn.normalize("百分之二十五"));
    }

    @Test
    void ITN万级与十位边界() {
        assertEquals("60000", InverseTextNormalizer.chineseToNumber("六万"));
        assertEquals("123", InverseTextNormalizer.chineseToNumber("一百二十三"));
        assertEquals("305", InverseTextNormalizer.chineseToNumber("三百零五"));
        assertEquals("20", InverseTextNormalizer.chineseToNumber("二十"));
        assertEquals("3.5", InverseTextNormalizer.chineseToNumber("三点五"));
    }
}
