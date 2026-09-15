package cn.chyuan.ai.domain.speech.adapter.port;

import java.util.List;

/**
 * ASR 转写执行端口（工单 0386 AU8）：音频引用→段序列；真实 ASR 服务挂雾，进程内假实现承接。
 */
public interface IAsrExecutorPort {

    /** 转写选项 */
    record Options(String language, boolean withWordTimestamps, boolean withSpeakerLabels) {
    }

    /** 转写段 */
    record Segment(long startMs, long endMs, String text, String speaker, List<String> words) {
    }

    /** 转写结果 */
    record Transcript(String audioRef, String language, List<Segment> segments, long durationMs) {
    }

    /**
     * 执行转写。
     *
     * @param audioRef 音频引用（对象存储键/URL）
     * @param options  选项
     */
    Transcript transcribe(String audioRef, Options options);
}
