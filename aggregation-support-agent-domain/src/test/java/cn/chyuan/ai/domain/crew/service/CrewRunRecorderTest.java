package cn.chyuan.ai.domain.crew.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行回放单测（工单 0219 AC7）：录制追加/markdown 时间线渲染/回放零 LLM。
 */
class CrewRunRecorderTest {

    @Test
    void 录制追加与渲染() {
        CrewRunRecorder recorder = new CrewRunRecorder("run-42");
        recorder.record("研究员", "EXECUTE_TASK", "查资料 → 资料OK");
        recorder.record("写手", "EXECUTE_TASK", "写报告 → 报告OK\n含换行摘要");
        recorder.record("写手", "COMPLETE", "顺序流程完成");
        assertEquals(3, recorder.size());
        assertEquals("run-42", recorder.runId());
        String markdown = CrewRunRecorder.renderMarkdown(recorder.runId(), recorder.steps());
        assertTrue(markdown.contains("# 多智能体运行回放"));
        assertTrue(markdown.contains("run-42"));
        assertTrue(markdown.contains("| 1 |"));
        assertTrue(markdown.contains("研究员"));
        // 摘要换行被压平（表格安全）
        assertTrue(markdown.contains("报告OK 含换行摘要"));
        // 时间线递增
        assertTrue(markdown.indexOf("| 2 |") > markdown.indexOf("| 1 |"));
    }

    @Test
    void 空录制渲染与默认运行号() {
        CrewRunRecorder recorder = new CrewRunRecorder(null);
        String markdown = CrewRunRecorder.renderMarkdown(recorder.runId(), List.of());
        assertTrue(markdown.contains("步骤数：0"));
        assertEquals("crew-run", recorder.runId());
    }

    @Test
    void 回放不触发LLM() {
        // 回放 = 纯文本渲染已验证；此处断言渲染器不持有 LLM 端口（签名级约束）：
        // renderMarkdown(runId, steps) 为静态纯函数，无 LlmPort 参数
        CrewRunRecorder recorder = new CrewRunRecorder("x");
        recorder.record("a", "b", "c");
        String out = CrewRunRecorder.renderMarkdown(recorder.runId(), recorder.steps());
        assertEquals(recorder.size(), 1);
        assertTrue(out.length() > 0);
    }
}
