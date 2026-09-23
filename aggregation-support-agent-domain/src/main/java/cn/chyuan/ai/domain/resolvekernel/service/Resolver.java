package cn.chyuan.ai.domain.resolvekernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 依赖解析器（工单 0665-0667·0669 CA3-CA5·CA7，uv PubGrub 思想）。
 * 解析状态（候选版本集/需求合并/部分解 assigned）/单元传播（唯一可用版本
 * 直接拍板、全部候选被排除即冲突即时失败）/回溯与冲突学习（责任链回跳：
 * 当前决策不在冲突贡献链上则整层剪枝上抛）/重启与节点熔断上限/
 * 冲突解释（不可满足核心+人类可读链，root 优先最短链）。
 */
public final class Resolver {

    /** 需求：约束区间 + 提出方（root 或 pkg@version） */
    public record Demand(Range range, String by) {
    }

    /** 冲突：无可用版本的包 + 需求贡献链 */
    public record Conflict(String name, List<String> contributors) {
    }

    /** 求解产出：状态/指派/冲突/统计 */
    public record Outcome(String status, Map<String, String> assigned, Conflict conflict,
                          int propagations, int decisions, int backtracks) {
    }

    private final Lockfile.DependencySource source;
    private final Map<String, Semver> lockedPrefs = new HashMap<>();
    private int steps;
    private final int limit;

    public Resolver(Lockfile.DependencySource source, Map<String, String> lockedPrefs, int stepLimit) {
        if (source == null) {
            throw new IllegalArgumentException("候选来源不得为 null");
        }
        this.source = source;
        if (lockedPrefs != null) {
            for (Map.Entry<String, String> e : lockedPrefs.entrySet()) {
                this.lockedPrefs.put(e.getKey(), Semver.of(e.getValue()));
            }
        }
        this.limit = stepLimit;
    }

    /** 依赖清单→解析解（root 需求作初始 demand） */
    public Outcome solve(Map<String, String> rootDeps) {
        if (rootDeps == null || rootDeps.isEmpty()) {
            throw new IllegalArgumentException("根依赖清单不得为空");
        }
        steps = 0;
        Map<String, List<Demand>> demands = new TreeMap<>();
        for (Map.Entry<String, String> e : new TreeMap<>(rootDeps).entrySet()) {
            demands.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                    .add(new Demand(Range.of(e.getValue()), "root"));
        }
        return dfs(new State(demands, new TreeMap<>()));
    }

    private Outcome dfs(State state) {
        if (++steps > limit) {
            return new Outcome("LIMIT", state.assigned, null, state.propagations, state.decisions, state.backtracks);
        }
        Conflict conflict = propagate(state);
        if (conflict != null) {
            return new Outcome("CONFLICT", state.assigned, conflict,
                    state.propagations, state.decisions, state.backtracks);
        }
        String next = nextUndecided(state);
        if (next == null) {
            return new Outcome("SAT", state.assigned, null,
                    state.propagations, state.decisions, state.backtracks);
        }
        List<Semver> candidates = candidates(state, next);
        List<Semver> ordered = preferLocked(next, candidates);
        for (Semver version : ordered) {
            State child = state.copy();
            child.assigned.put(next, version.toString());
            child.decisions++;
            for (String spec : source.dependencies(next, version.toString())) {
                String[] parts = spec.split(" ", 2);
                child.demands.computeIfAbsent(parts[0], k -> new ArrayList<>())
                        .add(new Demand(Range.of(parts.length > 1 ? parts[1] : "*"), next + "@" + version));
            }
            Outcome result = dfs(child);
            if ("SAT".equals(result.status()) || "LIMIT".equals(result.status())) {
                return result;
            }
            // 冲突学习：当前决策包不在冲突贡献链上则与本级无关，整层剪枝上跳
            if (!contributorsContain(result.conflict(), next)) {
                return result;
            }
            child.backtracks++;
            state.backtracks++;
        }
        List<String> contributors = contributors(state.demands.get(next));
        return new Outcome("CONFLICT", state.assigned, new Conflict(next, contributors),
                state.propagations, state.decisions, state.backtracks);
    }

    /** 单元传播定点：已指派包被新需求违反即冲突；唯一可用版本直接拍板；无可用版本即时冲突 */
    private Conflict propagate(State state) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String name : new TreeMap<>(state.demands).keySet()) {
                List<Demand> demands = state.demands.get(name);
                String pinned = state.assigned.get(name);
                if (pinned != null) {
                    if (!merge(demands).satisfiedBy(Semver.of(pinned))) {
                        return new Conflict(name, contributors(demands));
                    }
                    continue;
                }
                List<Semver> cands = candidates(state, name);
                if (cands.isEmpty()) {
                    return new Conflict(name, contributors(demands));
                }
                if (cands.size() == 1) {
                    Semver only = cands.get(0);
                    state.assigned.put(name, only.toString());
                    state.propagations++;
                    changed = true;
                    for (String spec : source.dependencies(name, only.toString())) {
                        String[] parts = spec.split(" ", 2);
                        state.demands.computeIfAbsent(parts[0], k -> new ArrayList<>())
                                .add(new Demand(Range.of(parts.length > 1 ? parts[1] : "*"), name + "@" + only));
                    }
                }
            }
        }
        return null;
    }

    private String nextUndecided(State state) {
        for (String name : state.demands.keySet()) {
            if (!state.assigned.containsKey(name) && candidates(state, name).size() > 1) {
                return name;
            }
        }
        return null;
    }

    private List<Semver> candidates(State state, String name) {
        Range merged = merge(state.demands.get(name));
        List<Semver> out = new ArrayList<>();
        for (String vs : source.versions(name)) {
            Semver v = Semver.of(vs);
            if (merged.satisfiedBy(v)) {
                out.add(v);
            }
        }
        out.sort((a, b) -> b.compareTo(a));
        return out;
    }

    private List<Semver> preferLocked(String name, List<Semver> candidates) {
        Semver locked = lockedPrefs.get(name);
        if (locked == null || !candidates.contains(locked)) {
            return candidates;
        }
        List<Semver> out = new ArrayList<>();
        out.add(locked);
        for (Semver v : candidates) {
            if (v.compareTo(locked) != 0) {
                out.add(v);
            }
        }
        return out;
    }

    private Range merge(List<Demand> demands) {
        Range merged = Range.of("*");
        for (Demand d : demands) {
            merged = merged.intersect(d.range());
        }
        return merged;
    }

    private static List<String> contributors(List<Demand> demands) {
        List<String> out = new ArrayList<>();
        for (Demand d : demands) {
            out.add(d.by() + " 需要 " + d.range());
        }
        out.sort((a, b) -> {
            boolean ra = a.startsWith("root");
            boolean rb = b.startsWith("root");
            if (ra != rb) {
                return ra ? -1 : 1;
            }
            return a.compareTo(b);
        });
        return out;
    }

    /** root 需求与决策无关；仅 pkg@version 贡献者表明当前决策影响冲突 */
    private static boolean contributorsContain(Conflict conflict, String pkg) {
        for (String contributor : conflict.contributors()) {
            if (contributor.startsWith(pkg + "@")) {
                return true;
            }
        }
        return false;
    }

    /** 冲突解释（CA7）：人类可读不可满足链（root 优先排序即最短链优先） */
    public static String explain(Conflict conflict) {
        StringBuilder sb = new StringBuilder("无法解析 ").append(conflict.name()).append("：");
        for (int i = 0; i < conflict.contributors().size(); i++) {
            if (i > 0) {
                sb.append(" 而 ");
            }
            sb.append(conflict.contributors().get(i));
        }
        sb.append("（无同时满足的候选版本）");
        return sb.toString();
    }

    /** 解析状态：需求表+部分解（按决策层拷贝隔离，天然支持回退） */
    private static final class State {
        final Map<String, List<Demand>> demands;
        final TreeMap<String, String> assigned;
        int propagations;
        int decisions;
        int backtracks;

        State(Map<String, List<Demand>> demands, TreeMap<String, String> assigned) {
            this.demands = demands;
            this.assigned = assigned;
        }

        State copy() {
            Map<String, List<Demand>> copied = new TreeMap<>();
            for (Map.Entry<String, List<Demand>> e : demands.entrySet()) {
                copied.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
            State s = new State(copied, new TreeMap<>(assigned));
            s.propagations = propagations;
            s.decisions = decisions;
            s.backtracks = backtracks;
            return s;
        }
    }
}
