package cn.chyuan.ai.domain.desiredkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Apply 收敛（工单 0674 CB4，terraform 思想）。
 * 拓扑序执行计划动作（create/update 先行、delete 逆序收尾）/
 * 失败中断并报告已应用清单/幂等重放（现态已等于目标即跳过）/顺序确定性。
 */
public final class Applier {

    /** 资源执行器（副作用端口，测试用记录实现） */
    public interface Executor {
        void create(String id, Map<String, String> attrs);

        void update(String id, Map<String, String> attrs);

        void delete(String id);
    }

    public record Report(List<String> executed, List<String> skipped, String failed, String error) {

        public boolean success() {
            return failed == null;
        }
    }

    private Applier() {
    }

    /**
     * 按拓扑序应用计划：非 delete 动作按依赖序执行，delete 逆序收尾。
     * 任一动作抛错即中断（返回已执行/跳过清单与失败动作）。
     * 幂等：create/update 时现态已等于目标属性则跳过。
     */
    public static Report apply(Planner.Plan plan, ResourceGraph graph,
                               StateManager mgr, Executor executor) {
        if (plan == null || graph == null || mgr == null || executor == null) {
            throw new IllegalArgumentException("应用入参不得为 null");
        }
        List<String> order = graph.topoOrder();
        List<Planner.Action> ordered = new ArrayList<>(plan.actions().stream()
                .filter(a -> !(a instanceof Planner.Delete)).toList());
        ordered.sort((a, b) -> Integer.compare(order.indexOf(a.resource()), order.indexOf(b.resource())));
        List<Planner.Action> deletes = plan.actions().stream()
                .filter(a -> a instanceof Planner.Delete)
                .sorted((a, b) -> Integer.compare(order.indexOf(b.resource()), order.indexOf(a.resource())))
                .toList();
        List<String> executed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        StateManager.State working = mgr.state().copy();
        for (Planner.Action action : ordered) {
            try {
                if (action instanceof Planner.Create create) {
                    if (working.resources().containsKey(create.resource())) {
                        skipped.add("skip " + create.resource() + "（已存在同态）");
                        continue;
                    }
                    executor.create(create.resource(), create.after());
                    working.resources().put(create.resource(), create.after());
                } else if (action instanceof Planner.Update update) {
                    Map<String, String> target = merge(working.resources().get(update.resource()), update);
                    Map<String, String> existing = working.resources().get(update.resource());
                    if (existing != null && existing.equals(target)) {
                        skipped.add("skip " + update.resource() + "（已等于目标态）");
                        continue;
                    }
                    executor.update(update.resource(), target);
                    working.resources().put(update.resource(), target);
                }
                executed.add(action.display());
            } catch (RuntimeException e) {
                if (!executed.isEmpty()) {
                    mgr.commit(working);
                }
                return new Report(List.copyOf(executed), List.copyOf(skipped),
                        action.display(), String.valueOf(e.getMessage()));
            }
        }
        for (Planner.Action action : deletes) {
            try {
                Planner.Delete delete = (Planner.Delete) action;
                executor.delete(delete.resource());
                working.resources().remove(delete.resource());
                executed.add(action.display());
            } catch (RuntimeException e) {
                if (!executed.isEmpty()) {
                    mgr.commit(working);
                }
                return new Report(List.copyOf(executed), List.copyOf(skipped),
                        action.display(), String.valueOf(e.getMessage()));
            }
        }
        if (!executed.isEmpty()) {
            mgr.commit(working);
        }
        return new Report(List.copyOf(executed), List.copyOf(skipped), null, null);
    }

    private static Map<String, String> merge(Map<String, String> base, Planner.Update update) {
        Map<String, String> out = new java.util.LinkedHashMap<>(base == null ? Map.of() : base);
        for (Planner.AttrChange change : update.changes()) {
            if (change.after() == null) {
                out.remove(change.attr());
            } else {
                out.put(change.attr(), change.after());
            }
        }
        return out;
    }
}
