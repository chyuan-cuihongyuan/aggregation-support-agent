package cn.chyuan.ai.domain.desiredkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 期望态端口+组合管线（工单 0678 CB8）。
 * parse→graph→plan→apply→state→drift 全链编排；记录型执行器；
 * 与 workflow 只读联动（plan 动作清单作任务形态，泛型字符串清单不 import
 * workflow，不改其任何类）/ desired-kernel.enabled 默认关（开启才改变行为）。
 */
public interface DesiredPort {

    /** 单轮运行产出：计划展示/已执行/跳过/失败/漂移/workflow 任务形态/serial */
    record RunResult(List<String> plan, List<String> executed, List<String> skipped,
                     String failed, List<String> drifts, List<String> workflowTasks, long serialAfter) {

        public boolean success() {
            return failed == null;
        }
    }

    /** 全链：解析→图→计划→应用→漂移检测→联动任务形态 */
    RunResult run(String configText, Map<String, Map<String, String>> observed, Set<String> forceNewAttrs);

    /** 上次 apply 后状态（drift 检测基准） */
    StateManager.State lastAppliedState();

    /** 内存假实现：解析器+图+计划+应用器+状态管理器全链 */
    class InMemoryDesired implements DesiredPort {

        private final StateManager mgr = new StateManager("inmemory-lineage");

        @Override
        public synchronized RunResult run(String configText, Map<String, Map<String, String>> observed, Set<String> forceNewAttrs) {
            ConfigModel.Config config = ConfigParser.parse(configText);
            ConfigParser.requireUniqueResources(config);
            ResourceGraph graph = ResourceGraph.from(config);
            Planner.Plan plan = Planner.plan(config, observed, forceNewAttrs == null ? Set.of() : forceNewAttrs);
            List<String> drifts = DriftDetector.detect(mgr.state(), observed).drifts()
                    .stream()
                    .map(d -> d.resource() + "." + d.attr() + ": " + d.applied() + " → " + d.observed())
                    .toList();
            List<String> workflowTasks = new ArrayList<>();
            for (Planner.Action action : plan.actions()) {
                workflowTasks.add("task:" + action.display());
            }
            RecordingExecutor executor = new RecordingExecutor();
            Applier.Report report = Applier.apply(plan, graph, mgr, executor);
            return new RunResult(plan.display(), report.executed(), report.skipped(),
                    report.failed(), drifts, List.copyOf(workflowTasks), mgr.state().serial());
        }

        @Override
        public synchronized StateManager.State lastAppliedState() {
            return mgr.state();
        }
    }

    /** 记录型执行器（副作用仅记录调用，幂等重放可校验） */
    class RecordingExecutor implements Applier.Executor {

        final List<String> calls = new ArrayList<>();

        @Override
        public void create(String id, Map<String, String> attrs) {
            calls.add("create " + id);
        }

        @Override
        public void update(String id, Map<String, String> attrs) {
            calls.add("update " + id);
        }

        @Override
        public void delete(String id) {
            calls.add("delete " + id);
        }
    }
}
