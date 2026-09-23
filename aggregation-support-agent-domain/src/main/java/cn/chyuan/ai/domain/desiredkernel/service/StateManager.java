package cn.chyuan.ai.domain.desiredkernel.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 状态管理（工单 0675 CB5，terraform 思想）。
 * 状态文件 serial 单调递增与 lineage 标识/并发状态锁（二次加锁拒绝、
 * 释放后可加、令牌校验）/refresh 合并现态（漂移检测前置步骤）。
 */
public final class StateManager {

    /** 状态快照：serial+lineage+资源表（id→属性） */
    public static final class State {
        private final TreeMap<String, Map<String, String>> resources = new TreeMap<>();
        private long serial;
        private final String lineage;

        State(String lineage) {
            this.lineage = lineage;
        }

        public long serial() {
            return serial;
        }

        public String lineage() {
            return lineage;
        }

        public Map<String, Map<String, String>> resources() {
            return resources;
        }

        State copy() {
            State out = new State(lineage);
            out.serial = serial;
            for (Map.Entry<String, Map<String, String>> e : resources.entrySet()) {
                out.resources.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
            }
            return out;
        }
    }

    private final State state;
    private String lockHolder;

    public StateManager(String lineage) {
        if (lineage == null || lineage.isBlank()) {
            throw new IllegalArgumentException("lineage 不得为空");
        }
        this.state = new State(lineage);
    }

    public State state() {
        return state;
    }

    /** 提交新状态：serial 单调递增 */
    public void commit(State next) {
        requireUnlocked();
        next.serial = state.serial + 1;
        state.resources.clear();
        state.resources.putAll(next.resources);
        state.serial = next.serial;
    }

    /** refresh：用现态观察值合并（不递增 serial，非配置变更） */
    public void refresh(Map<String, Map<String, String>> observed) {
        requireUnlocked();
        state.resources.clear();
        for (Map.Entry<String, Map<String, String>> e : observed.entrySet()) {
            state.resources.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
        }
    }

    /** 状态锁：持有期间二次加锁（任何令牌）拒绝 */
    public void lock(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("锁令牌不得为空");
        }
        if (lockHolder != null) {
            throw new IllegalArgumentException("状态已被 " + lockHolder + " 加锁");
        }
        lockHolder = token;
    }

    public void unlock(String token) {
        if (!token.equals(lockHolder)) {
            throw new IllegalArgumentException("锁令牌不匹配");
        }
        lockHolder = null;
    }

    public boolean locked() {
        return lockHolder != null;
    }

    private void requireUnlocked() {
        if (lockHolder != null) {
            throw new IllegalArgumentException("状态被锁，禁止提交/刷新");
        }
    }
}
