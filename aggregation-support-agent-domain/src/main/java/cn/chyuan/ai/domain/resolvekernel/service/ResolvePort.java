package cn.chyuan.ai.domain.resolvekernel.service;

import java.util.List;
import java.util.Map;

/**
 * 解析端口+组合管线（工单 0670 CA8）。
 * 依赖清单→解析解+锁文件+统计；已锁版本偏好（resolveWithLock 优先复用）；
 * 与 vcskernel 只读联动（锁文件规范文本作 blob 输入形态，泛型入参不 import
 * vcskernel，不改其任何类）/ resolve-kernel.enabled 默认关（开启才改变行为）。
 */
public interface ResolvePort {

    enum Status { SAT, UNSAT, LIMIT }

    /** 求解统计：传播/决策/回跳/冲突计数与状态 */
    record Stat(int propagations, int decisions, int backtracks, int conflicts, Status status) {
    }

    /** 解析产出：状态/指派/锁文件/冲突解释/统计 */
    record Result(Status status, Map<String, String> pinned, Lockfile lock, String explanation, Stat stat) {
    }

    /** 候选来源（包名→版本清单与依赖清单） */
    interface CandidateSource extends Lockfile.DependencySource {
    }

    Result resolve(Map<String, String> rootDeps, CandidateSource source);

    /** 已锁版本偏好：可满足时优先复用旧锁版本，最小化变更 */
    Result resolveWithLock(Map<String, String> rootDeps, CandidateSource source, Lockfile previousLock);

    /** 与 vcskernel 只读联动：锁文件规范文本作 blob 提交输入形态 */
    String lockTextForCommit(Result result);

    /** 内存假实现：Resolver+Lockfile 全链 */
    class InMemoryResolver implements ResolvePort {

        @Override
        public Result resolve(Map<String, String> rootDeps, CandidateSource source) {
            return run(rootDeps, source, null);
        }

        @Override
        public Result resolveWithLock(Map<String, String> rootDeps, CandidateSource source, Lockfile previousLock) {
            return run(rootDeps, source, previousLock);
        }

        private Result run(Map<String, String> rootDeps, CandidateSource source, Lockfile previousLock) {
            if (source == null) {
                throw new IllegalArgumentException("候选来源不得为 null");
            }
            Map<String, String> prefs = new java.util.TreeMap<>();
            if (previousLock != null) {
                for (Lockfile.Locked locked : previousLock.sorted()) {
                    prefs.put(locked.name(), locked.version());
                }
            }
            Resolver resolver = new Resolver(source, prefs, 10_000);
            Resolver.Outcome outcome = resolver.solve(rootDeps);
            Status status = "SAT".equals(outcome.status()) ? Status.SAT
                    : "LIMIT".equals(outcome.status()) ? Status.LIMIT : Status.UNSAT;
            Lockfile lock = status == Status.SAT ? Lockfile.from(outcome.assigned(), source) : null;
            String explanation = status == Status.UNSAT && outcome.conflict() != null
                    ? Resolver.explain(outcome.conflict()) : null;
            int conflicts = status == Status.UNSAT ? 1 : 0;
            Stat stat = new Stat(outcome.propagations(), outcome.decisions(),
                    outcome.backtracks(), conflicts, status);
            return new Result(status, outcome.assigned(), lock, explanation, stat);
        }

        @Override
        public String lockTextForCommit(Result result) {
            if (result == null || result.lock() == null) {
                throw new IllegalArgumentException("无锁文件可提交");
            }
            return result.lock().toCanonicalText();
        }
    }
}
