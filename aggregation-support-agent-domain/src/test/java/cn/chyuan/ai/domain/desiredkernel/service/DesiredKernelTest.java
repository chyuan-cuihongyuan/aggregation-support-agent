package cn.chyuan.ai.domain.desiredkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 声明式期望态内核域单测（工单 0671-0676 CB1-CB6，terraform 思想）。
 * HCL 子集解析错误定位与重复标识拒绝/资源图引用边与 Kahn 环报告/
 * plan 三动作与替换标记/apply 拓扑序失败中断幂等重放/
 * 状态 serial·lineage 与并发锁/drift 字段级检测与以配置为准收敛。
 */
class DesiredKernelTest {

    static final String CONFIG = """
            # 基础设施期望态
            resource "server" "db" {
              image = "postgres:16"
              port  = 5432
            }
            resource "server" "web" {
              image      = "nginx:1.25"
              port       = 80
              https      = true
              depends_on = [server.db]
            }
            output "web_host" {
              value = server.web.host
            }
            """;

    @Test
    void parserBlocksAttrsAndValues() {
        ConfigModel.Config config = ConfigParser.parse(CONFIG);
        assertEquals(3, config.blocks().size());
        ConfigModel.Block web = config.byType("resource").stream()
                .filter(b -> b.identity().equals("server.web")).findFirst().orElseThrow();
        assertEquals("nginx:1.25", ((ConfigModel.Str) web.attrs().get("image")).s());
        assertEquals(80, ((ConfigModel.Num) web.attrs().get("port")).d());
        assertTrue(((ConfigModel.Bool) web.attrs().get("https")).b());
        ConfigModel.ListV depList = (ConfigModel.ListV) web.attrs().get("depends_on");
        ConfigModel.Ref dep = (ConfigModel.Ref) depList.items().get(0);
        assertEquals(List.of("server", "db"), dep.path());
        assertEquals("web_host", config.byType("output").get(0).identity());
    }

    @Test
    void parserErrorsLocatedAndDuplicatesRejected() {
        IllegalArgumentException unclosed = assertThrows(IllegalArgumentException.class,
                () -> ConfigParser.parse("resource \"a\" \"b\" {\n  image = \"x\"\n"));
        assertTrue(unclosed.getMessage().contains("}"), "缺块尾括号要提示：实际消息=" + unclosed.getMessage());
        IllegalArgumentException badChar = assertThrows(IllegalArgumentException.class,
                () -> ConfigParser.parse("resource \"a\" \"b\" {\n  image = %\n}"));
        assertTrue(badChar.getMessage().contains("第 2 行"), "错误定位行号");
        IllegalArgumentException dup = assertThrows(IllegalArgumentException.class,
                () -> ConfigParser.requireUniqueResources(ConfigParser.parse("""
                        resource "a" "b" {
                          x = 1
                        }
                        resource "a" "b" {
                          x = 2
                        }
                        """)));
        assertTrue(dup.getMessage().contains("a.b"));
        assertThrows(IllegalArgumentException.class, () -> ConfigParser.parse("   "));
    }

    @Test
    void graphRefsTopoAndCycleReport() {
        ConfigModel.Config config = ConfigParser.parse(CONFIG);
        ResourceGraph graph = ResourceGraph.from(config);
        assertEquals(Set.of("server.db", "server.web"), graph.resources());
        List<String> order = graph.topoOrder();
        assertTrue(order.indexOf("server.db") < order.indexOf("server.web"), "依赖先行");
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> ResourceGraph.from(ConfigParser.parse("""
                        resource "a" "one" {
                          x = server.missing.host
                        }
                        """)));
        assertTrue(unknown.getMessage().contains("未知资源引用"));
        IllegalArgumentException cycle = assertThrows(IllegalArgumentException.class,
                () -> ResourceGraph.from(ConfigParser.parse("""
                        resource "a" "one" {
                          x = a.two.p
                        }
                        resource "a" "two" {
                          x = a.one.p
                        }
                        """)).topoOrder(), "环检测在拓扑排序阶段触发");
        assertTrue(cycle.getMessage().contains("环"));
    }

    @Test
    void planCreateUpdateDeleteAndReplacement() {
        ConfigModel.Config desired = ConfigParser.parse("""
                resource "srv" "app" {
                  image = "app:2.0"
                  size  = 4
                }
                resource "srv" "old" {
                  image = "old:1"
                }
                """);
        Map<String, Map<String, String>> current = Map.of(
                "srv.app", Map.of("image", "app:1.0", "size", "4"),
                "srv.old", Map.of("image", "old:1"),
                "srv.gone", Map.of("image", "gone:1"));
        Planner.Plan plan = Planner.plan(desired, current, Set.of("image"));
        assertEquals(2, plan.actions().size(), "update + delete（srv.old 无差异不产生动作）");
        Planner.Update update = (Planner.Update) plan.actions().get(0);
        assertEquals("srv.app", update.resource());
        assertTrue(update.replacement(), "image 为 force_new 属性 → 替换型");
        assertEquals("app:1.0", update.changes().get(0).before());
        assertEquals("app:2.0", update.changes().get(0).after());
        assertEquals("srv.gone", ((Planner.Delete) plan.actions().get(1)).resource());
        assertTrue(plan.hasChanges());

        Planner.Plan empty = Planner.plan(desired, Map.of(
                "srv.app", Map.of("image", "app:2.0", "size", "4"),
                "srv.old", Map.of("image", "old:1")), Set.of());
        assertFalse(empty.hasChanges(), "无差异空计划");
        assertTrue(Planner.plan(desired, current, Set.of()).actions().get(0) instanceof Planner.Update);
        assertFalse(((Planner.Update) Planner.plan(desired, current, Set.of()).actions().get(0)).replacement(),
                "无 force_new 时为普通 update");
    }

    @Test
    void applyTopoOrderFailureAbortAndIdempotentReplay() {
        ConfigModel.Config config = ConfigParser.parse(CONFIG);
        ResourceGraph graph = ResourceGraph.from(config);
        Planner.Plan plan = Planner.plan(config, Map.of(), Set.of());
        StateManager mgr = new StateManager("lineage-a");
        List<String> calls = new java.util.ArrayList<>();
        Applier.Report report = Applier.apply(plan, graph, mgr, new Applier.Executor() {
            @Override
            public void create(String id, Map<String, String> attrs) {
                calls.add(id);
            }

            @Override
            public void update(String id, Map<String, String> attrs) {
            }

            @Override
            public void delete(String id) {
            }
        });
        assertTrue(report.success());
        assertEquals(List.of("server.db", "server.web"), calls, "拓扑序确定性");
        assertEquals(1, mgr.state().serial());

        // 失败中断：db 建后 web 抛错
        StateManager mgr2 = new StateManager("lineage-b");
        Applier.Report failed = Applier.apply(plan, graph, mgr2, new Applier.Executor() {
            @Override
            public void create(String id, Map<String, String> attrs) {
                if (id.equals("server.web")) {
                    throw new IllegalStateException("配额不足");
                }
            }

            @Override
            public void update(String id, Map<String, String> attrs) {
            }

            @Override
            public void delete(String id) {
            }
        });
        assertFalse(failed.success());
        assertEquals("create server.web", failed.failed());
        assertTrue(failed.error().contains("配额不足"));
        assertEquals(List.of("create server.db"), failed.executed(), "失败中断保留已应用");
        assertEquals(1, mgr2.state().serial(), "中断态已提交");

        // 幂等重放：同计划对已应用态全跳过
        Applier.Report replay = Applier.apply(plan, graph, mgr, new Applier.Executor() {
            @Override
            public void create(String id, Map<String, String> attrs) {
            }

            @Override
            public void update(String id, Map<String, String> attrs) {
            }

            @Override
            public void delete(String id) {
            }
        });
        assertEquals(2, replay.skipped().size(), "已等于目标态全部跳过");
        assertTrue(replay.executed().isEmpty());
    }

    @Test
    void stateSerialLineageAndLocking() {
        StateManager mgr = new StateManager("ln-123");
        assertEquals("ln-123", mgr.state().lineage());
        assertEquals(0, mgr.state().serial());
        mgr.lock("runner-1");
        assertTrue(mgr.locked());
        assertThrows(IllegalArgumentException.class, () -> mgr.lock("runner-2"), "二次加锁拒绝");
        assertThrows(IllegalArgumentException.class, () -> mgr.commit(mgr.state().copy()), "持锁禁止提交");
        assertThrows(IllegalArgumentException.class, () -> mgr.unlock("wrong"), "令牌不匹配");
        mgr.unlock("runner-1");
        assertFalse(mgr.locked());
        mgr.lock("runner-2");
        mgr.unlock("runner-2");
        mgr.commit(mgr.state().copy());
        assertEquals(1, mgr.state().serial(), "提交后 serial 单调递增");
        assertThrows(IllegalArgumentException.class, () -> new StateManager(" "));
    }

    @Test
    void driftDetectAndConfigWinsConverge() {
        StateManager.State applied = new StateManager("ln").state();
        applied.resources().put("srv.app", new java.util.LinkedHashMap<>(Map.of("image", "app:1.0")));
        Map<String, Map<String, String>> observed = Map.of(
                "srv.app", Map.of("image", "app:9.9", "size", "8"));
        DriftDetector.Report report = DriftDetector.detect(applied, observed);
        assertTrue(report.hasDrift());
        assertEquals(2, report.drifts().size(), "image 漂移 + 新增 size");
        assertEquals("app:1.0", report.drifts().get(0).applied());
        assertEquals("app:9.9", report.drifts().get(0).observed());

        DriftDetector.Report none = DriftDetector.detect(applied,
                Map.of("srv.app", Map.of("image", "app:1.0")));
        assertFalse(none.hasDrift(), "无漂移空报告");

        ConfigModel.Config desired = ConfigParser.parse("""
                resource "srv" "app" {
                  image = "app:1.0"
                }
                """);
        DriftDetector.Report converge = DriftDetector.converge(desired, observed, Set.of());
        assertEquals(List.of("update srv.app (2 attrs)"), converge.convergenceActions(),
                "以配置为准收敛为 update 动作（image 回迁 + size 移除）");
    }
}
