package cn.chyuan.ai.domain.crew.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文裁剪器单测（工单 0216 AC4）：优先级保留/预算截断/省略标注。
 */
class ContextTrimmerTest {

    @Test
    void 无预算不裁剪() {
        ContextTrimmer.TrimmedContext r = ContextTrimmer.trim("任务", List.of("结论1"), List.of("附加"), 0);
        assertFalse(r.truncated());
        assertTrue(r.text().contains("任务"));
        assertTrue(r.text().contains("结论1"));
        assertTrue(r.text().contains("附加"));
    }

    @Test
    void 预算内保任务与最新结论() {
        // 任务 4 字符 + 每条结论 6 字符；预算 10：任务全保 + 仅最新一条结论
        ContextTrimmer.TrimmedContext r = ContextTrimmer.trim("任务描述",
                List.of("旧结论甲甲甲", "新结论乙乙乙"), List.of(), 10);
        assertTrue(r.text().startsWith("任务描述"));
        assertTrue(r.text().contains("新结论乙乙乙"));
        assertFalse(r.text().contains("旧结论甲甲甲"));
        assertTrue(r.truncated());
        assertEquals(1, r.omittedLines());
    }

    @Test
    void 任务超预算尾部截断() {
        ContextTrimmer.TrimmedContext r = ContextTrimmer.trim("很长的任务描述需要被截断",
                List.of(), List.of(), 5);
        assertTrue(r.truncated());
        assertEquals("很长的任务…", r.text());
        assertEquals(0, r.omittedLines());
    }

    @Test
    void 附加行低优先级() {
        // 预算 4：任务 2 字符全保，附加行 3 字符装不下 → 省略
        ContextTrimmer.TrimmedContext r = ContextTrimmer.trim("任务", List.of(), List.of("附加行"), 4);
        assertTrue(r.text().contains("任务"));
        assertFalse(r.text().contains("附加行"));
        assertEquals(1, r.omittedLines());
    }
}
