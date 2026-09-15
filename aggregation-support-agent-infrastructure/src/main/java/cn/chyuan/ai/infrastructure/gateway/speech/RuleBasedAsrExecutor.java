package cn.chyuan.ai.infrastructure.gateway.speech;

import cn.chyuan.ai.domain.speech.adapter.port.IAsrExecutorPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则合成 ASR 执行器（工单 0386 AU8 假实现）。
 * 无真实 ASR 服务环境下的进程内实现：录制样本库（audioRef→段序列）回放；
 * 未录制引用按规则合成单段占位转写。speech.enabled 默认关，开启才装配。
 */
@Component
@ConditionalOnProperty(name = "speech.enabled", havingValue = "true")
public class RuleBasedAsrExecutor implements IAsrExecutorPort {

    /** 录制样本库（audioRef→转写结果） */
    private final Map<String, Transcript> recorded = new ConcurrentHashMap<>();

    /** 登记录制样本（录制回放一致性来源） */
    public void record(String audioRef, Transcript transcript) {
        recorded.put(audioRef, transcript);
    }

    @Override
    public Transcript transcribe(String audioRef, Options options) {
        if (audioRef == null || audioRef.isBlank()) {
            throw new IllegalArgumentException("音频引用不可为空");
        }
        Transcript hit = recorded.get(audioRef);
        if (hit != null) {
            return hit;
        }
        // 规则合成：单段占位转写（标注合成来源）
        List<Segment> segments = new ArrayList<>();
        segments.add(new Segment(0L, 1000L,
                "[合成转写] " + audioRef, options != null && options.withSpeakerLabels() ? "SPEAKER_00" : null,
                List.of()));
        return new Transcript(audioRef, options == null ? "zh" : options.language(), segments, 1000L);
    }
}
