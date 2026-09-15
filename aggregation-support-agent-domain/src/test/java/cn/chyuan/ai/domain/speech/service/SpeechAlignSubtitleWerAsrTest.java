package cn.chyuan.ai.domain.speech.service;

import cn.chyuan.ai.domain.speech.adapter.port.IAsrExecutorPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AU4-AU8 单测（工单 0382/0383/0384/0385/0386）：时间戳对齐/说话人轮转/字幕导出/WER 指标/ASR 端口。
 */
class SpeechAlignSubtitleWerAsrTest {

    @Test
    void 词级时间戳比例分配与单调校验() {
        TimestampAligner aligner = new TimestampAligner();
        // 词长 2/2/1，段 0-1000：比例 400/400/200
        List<TimestampAligner.WordTiming> timings = aligner.align(
                List.of("你好", "世界", "啊"), 0L, 1000L, java.util.Set.of());
        assertEquals(3, timings.size());
        assertEquals(0L, timings.get(0).startMs());
        assertEquals(400L, timings.get(0).endMs());
        assertEquals(400L, timings.get(1).startMs());
        assertEquals(800L, timings.get(1).endMs());
        // 末词兜底到段尾
        assertEquals(1000L, timings.get(2).endMs());
        // 跳过词零长占位
        List<TimestampAligner.WordTiming> skipped = aligner.align(
                List.of("你好", "[噪声]", "世界"), 0L, 1000L, java.util.Set.of(1));
        assertEquals(skipped.get(1).startMs(), skipped.get(1).endMs());
        // 单调性成立
        for (int i = 1; i < timings.size(); i++) {
            assertTrue(timings.get(i).startMs() >= timings.get(i - 1).endMs());
        }
        // 非法段区间拒绝
        assertThrows(IllegalArgumentException.class, () -> aligner.align(List.of("x"), 100L, 50L, null));
    }

    @Test
    void 说话人轮转变化点与防抖() {
        SpeakerTurnDetector detector = new SpeakerTurnDetector(2.0, 50.0, 3);
        double[] energy = {0.8, 0.8, 0.8, 0.2, 0.2, 0.2, 0.2, 0.9, 0.9, 0.9};
        double[] f0 = {100, 100, 100, 100, 100, 100, 100, 220, 220, 220};
        SpeakerTurnDetector.Detection detection = detector.detect(energy, f0);
        // 帧变化点：3（0.8→0.2 跌变 4x）与 7（0.2→0.9 跳变+基频双跳），间隔 4>=minTurn 保留 → 3 段交替
        assertEquals(3, detection.turns().size());
        assertEquals("SPEAKER_00", detection.turns().get(0).speaker());
        assertEquals("SPEAKER_01", detection.turns().get(1).speaker());
        assertEquals("SPEAKER_00", detection.turns().get(2).speaker());
        assertTrue(detection.changePoints().size() >= 2);
        // 抖动防抖：1 帧内的近距变化点仅留最强
        SpeakerTurnDetector jittery = new SpeakerTurnDetector(1.5, 10.0, 5);
        double[] e2 = {0.9, 0.1, 0.9, 0.1, 0.9, 0.9, 0.9, 0.9, 0.9, 0.9};
        double[] f2 = {100, 100, 100, 100, 100, 100, 100, 100, 100, 100};
        SpeakerTurnDetector.Detection d2 = jittery.detect(e2, f2);
        // 近距抖动收敛：不超过 2 段
        assertTrue(d2.turns().size() <= 2);
        // 非法输入
        assertThrows(IllegalArgumentException.class, () -> detector.detect(new double[3], new double[2]));
    }

    @Test
    void 字幕SRVT导出与往返一致() {
        SubtitleExporter exporter = new SubtitleExporter();
        List<SubtitleExporter.Cue> cues = List.of(
                new SubtitleExporter.Cue(1, 0L, 3_500L, "你好世界", "SPEAKER_00"),
                new SubtitleExporter.Cue(2, 3_660_000L, 3_661_500L, "第二句", null));
        String srt = exporter.toSrt(cues);
        assertTrue(srt.contains("00:00:03,500"), srt);
        assertTrue(srt.contains("01:01:00,000"));
        // 往返一致
        List<SubtitleExporter.Cue> parsed = exporter.parseSrt(srt);
        assertEquals(2, parsed.size());
        assertEquals("你好世界", parsed.get(0).text());
        assertEquals("SPEAKER_00", parsed.get(0).speaker());
        assertEquals(3_500L, parsed.get(0).endMs());
        assertEquals(3_661_500L, parsed.get(1).endMs());
        // VTT 头与点号毫秒
        String vtt = exporter.toVtt(cues);
        assertTrue(vtt.startsWith("WEBVTT"));
        assertTrue(vtt.contains("00:00:03.500"));
    }

    @Test
    void WER编辑距离对齐与CER空序列边界() {
        TranscriptionMetrics metrics = new TranscriptionMetrics();
        // ref=你好世界 hyp=你好时代：1 替换
        var r1 = metrics.compute(List.of("你好", "世界"), List.of("你好", "时代"));
        assertEquals(1, r1.substitutions());
        assertEquals(0.5, r1.wer());
        // ref=你好世界 hyp=你好呀世界：1 插入
        // ref=你好世界 hyp=你好呀世界：1 插入（WER 分母=参考词数）
        var r2 = metrics.compute(List.of("你好", "世界"), List.of("你好", "呀", "世界"));
        assertEquals(1, r2.insertions());
        assertEquals(0.5, r2.wer(), 1e-4);
        // 删除
        var r3 = metrics.compute(List.of("你好", "世界", "加油"), List.of("你好", "加油"));
        assertEquals(1, r3.deletions());
        // CER：字符级
        assertTrue(r1.cer() > 0);
        // 空序列边界
        assertEquals(0.0, metrics.compute(List.of(), List.of()).wer());
        assertEquals(1.0, metrics.compute(List.of(), List.of("x")).wer());
        assertEquals(1.0, metrics.compute(List.of("x"), List.of()).wer());
    }

    @Test
    void ASR端口录制回放一致与规则合成兜底() {
        // 域内假实现验证端口契约（infrastructure 侧 RuleBasedAsrExecutor 为同语义装配实现）
        IAsrExecutorPort fake = (audioRef, options) -> new IAsrExecutorPort.Transcript(
                audioRef, options.language(),
                List.of(new IAsrExecutorPort.Segment(0L, 1500L, "你好世界", "SPEAKER_00", List.of("你好", "世界"))),
                1500L);
        var out = fake.transcribe("oss://a.wav", new IAsrExecutorPort.Options("zh", true, true));
        assertEquals("oss://a.wav", out.audioRef());
        assertEquals(1, out.segments().size());
        assertEquals("SPEAKER_00", out.segments().get(0).speaker());
        assertEquals("你好世界", out.segments().get(0).text());
    }
}
