package cn.chyuan.ai.domain.desiredkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DesiredPort 组合管线测试（工单 0678 CB8，terraform 思想）。
 * parse→graph→plan→apply→state→drift 全链/幂等第二轮/workflow 任务只读联动
 * 形态/漂移清单/非法配置拒绝。
 */
class DesiredPortPipelineTest {

    static final String CONFIG = """
            resource "srv" "db" {
              image = "pg:16"
              port  = 5432
            }
            resource "srv" "web" {
              image      = "nginx:1.25"
              depends_on = [srv.db]
            }
            """;

    @Test
    void portRunCreatesAndSerialAdvances() {
        DesiredPort port = new DesiredPort.InMemoryDesired();
        DesiredPort.RunResult run = port.run(CONFIG, Map.of(), Set.of());
        assertTrue(run.success());
        assertEquals(List.of("create srv.db", "create srv.web"), run.plan(), "计划含依赖序");
        assertEquals(2, run.executed().size());
        assertEquals(List.of("task:create srv.db", "task:create srv.web"), run.workflowTasks(),
                "workflow 任务只读联动形态");
        assertEquals(1, run.serialAfter());
        assertEquals(2, port.lastAppliedState().resources().size());
    }

    @Test
    void portSecondRunIdempotentWithObservedState() {
        DesiredPort port = new DesiredPort.InMemoryDesired();
        port.run(CONFIG, Map.of(), Set.of());
        Map<String, Map<String, String>> applied = Map.of(
                "srv.db", Map.of("image", "pg:16", "port", "5432"),
                "srv.web", Map.of("image", "nginx:1.25"));
        DesiredPort.RunResult second = port.run(CONFIG, applied, Set.of());
        assertTrue(second.plan().isEmpty(), "同配置同现态应为空计划");
        assertTrue(second.executed().isEmpty());
        assertEquals(1, second.serialAfter(), "空计划不推进 serial");
    }

    @Test
    void portDriftBetweenLastAppliedAndObserved() {
        DesiredPort port = new DesiredPort.InMemoryDesired();
        port.run(CONFIG, Map.of(), Set.of());
        Map<String, Map<String, String>> drifted = Map.of(
                "srv.db", Map.of("image", "pg:17", "port", "5432"),
                "srv.web", Map.of("image", "nginx:1.25"));
        DesiredPort.RunResult run = port.run(CONFIG, drifted, Set.of());
        assertTrue(run.drifts().size() >= 1, "现态 vs 上次 apply 漂移可见");
        assertTrue(run.drifts().get(0).contains("pg:16 → pg:17"), run.drifts().toString());
        assertEquals("update srv.db (1 attrs)", run.plan().get(0), "期望态覆盖策略（config wins）");
    }

    @Test
    void portRejectsIllegalConfig() {
        DesiredPort port = new DesiredPort.InMemoryDesired();
        assertThrows(IllegalArgumentException.class, () -> port.run("resource srv {", Map.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> port.run("", Map.of(), Set.of()));
    }
}
