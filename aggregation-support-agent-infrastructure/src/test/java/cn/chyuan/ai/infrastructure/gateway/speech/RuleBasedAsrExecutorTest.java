package cn.chyuan.ai.infrastructure.gateway.speech;

import cn.chyuan.ai.domain.speech.adapter.port.IAsrExecutorPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AU8 假执行器单测（工单 0386）：录制回放一致 + 规则合成兜底。
 */
class RuleBasedAsrExecutorTest {

    @Test
    void 录制样本回放一致与合成兜底() {
        RuleBasedAsrExecutor executor = new RuleBasedAsrExecutor();
        IAsrExecutorPort.Options options = new IAsrExecutorPort.Options("zh", true, true);
        // 未录制引用：规则合成单段占位
        var synthesized = executor.transcribe("oss://unknown.wav", options);
        assertTrue(synthesized.segments().get(0).text().startsWith("[合成转写]"));
        assertEquals(1000L, synthesized.durationMs());
        // 登记录制样本后回放一致（两次结果相等）
        IAsrExecutorPort.Transcript recorded = new IAsrExecutorPort.Transcript(
                "oss://known.wav", "zh",
                List.of(new IAsrExecutorPort.Segment(0L, 2000L, "你好世界", "SPEAKER_00", List.of("你好", "世界"))),
                2000L);
        executor.record("oss://known.wav", recorded);
        assertEquals(recorded, executor.transcribe("oss://known.wav", options));
        assertEquals(executor.transcribe("oss://known.wav", options),
                executor.transcribe("oss://known.wav", options));
        // 空引用拒绝
        assertThrows(IllegalArgumentException.class, () -> executor.transcribe(" ", options));
    }
}
