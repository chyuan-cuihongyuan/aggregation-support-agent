package cn.chyuan.ai.test.domain.armory;

import cn.chyuan.ai.domain.agent.service.armory.matter.mcp.server.InternalDocsTools;
import cn.chyuan.ai.domain.agent.service.armory.matter.mcp.server.ToolResultSpillGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InternalDocsTools 守卫接线行为测试（SELFLOOP4 loop-427，工单 0652/0653）。
 * 只验证「toJson 出口过 bound」的接线事实；守卫自身语义由 ToolResultSpillGuardTest 行为锁覆盖。
 */
class InternalDocsToolsSpillWiringTest {

    private InternalDocsTools tools;
    private ToolResultSpillGuard guard;

    @BeforeEach
    void setUp() {
        tools = new InternalDocsTools();
        guard = new ToolResultSpillGuard();
        ReflectionTestUtils.setField(guard, "maxChars", 200);
        ReflectionTestUtils.setField(guard, "maxAlerts", 20);
        ReflectionTestUtils.setField(guard, "maxTimeseriesPoints", 120);
        ReflectionTestUtils.setField(guard, "maxLogEntries", 50);
        ReflectionTestUtils.setField(tools, "spillGuard", guard);
    }

    @Test
    @DisplayName("预算内结果原样通过（不受守卫影响）")
    void smallResultPassesUnchanged() {
        String out = ReflectionTestUtils.invokeMethod(tools, "toJson",
                (Object) new TinyPayload("ok"));
        assertThat(out).contains("\"v\":\"ok\"");
        assertThat(out).doesNotContain("[spill]");
    }

    @Test
    @DisplayName("超预算结果被截断且带模型可见 spill 标记（G43 接线事实）")
    void oversizedResultTruncatedWithSpillMark() {
        char[] big = new char[4096];
        java.util.Arrays.fill(big, 'x');
        String out = ReflectionTestUtils.invokeMethod(tools, "toJson",
                (Object) new TinyPayload(new String(big)));
        assertThat(out.length()).isLessThan(4096);
        assertThat(out).contains("[spill]");
        assertThat(out).endsWith("增大步长。");
    }

    /** 最小可序列化负载 */
    public record TinyPayload(String v) {}
}
