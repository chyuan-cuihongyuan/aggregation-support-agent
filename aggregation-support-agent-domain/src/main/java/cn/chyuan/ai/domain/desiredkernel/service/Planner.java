package cn.chyuan.ai.domain.desiredkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.chyuan.ai.domain.desiredkernel.service.ConfigModel.*;

/**
 * Plan 差异（工单 0673 CB3，terraform 思想）。
 * 期望态 vs 现态三动作（create/update/delete）/属性级 before/after 明细/
 * 替换型变更标记（force_new 属性变化）/无差异空计划。
 */
public final class Planner {

    /** 动作：create/update/delete（update 含属性级变更明细与替换标记） */
    public sealed interface Action {
        String resource();

        String display();
    }

    public record Create(String resource, Map<String, String> after) implements Action {
        @Override
        public String display() {
            return "create " + resource;
        }
    }

    public record Update(String resource, List<AttrChange> changes, boolean replacement) implements Action {
        @Override
        public String display() {
            return (replacement ? "replace " : "update ") + resource + " (" + changes.size() + " attrs)";
        }
    }

    public record Delete(String resource, String before) implements Action {
        @Override
        public String display() {
            return "delete " + resource;
        }
    }

    public record AttrChange(String attr, String before, String after) {
    }

    public record Plan(List<Action> actions) {

        public boolean hasChanges() {
            return !actions.isEmpty();
        }

        public List<String> display() {
            return actions.stream().map(Action::display).toList();
        }
    }

    private Planner() {
    }

    /**
     * 差异计划：期望配置 vs 现态。forceNew 属性（变更即替换）变化时
     * update 升级为 replace 标记。纯函数不改入参。
     */
    public static Plan plan(Config desired, Map<String, Map<String, String>> current, Set<String> forceNewAttrs) {
        if (desired == null || current == null) {
            throw new IllegalArgumentException("计划入参不得为 null");
        }
        List<Action> actions = new ArrayList<>();
        Map<String, Map<String, String>> desiredMap = new LinkedHashMap<>();
        for (Block block : desired.byType("resource")) {
            desiredMap.put(block.identity(), renderAttrs(block));
        }
        for (Map.Entry<String, Map<String, String>> e : desiredMap.entrySet()) {
            Map<String, String> existing = current.get(e.getKey());
            if (existing == null) {
                actions.add(new Create(e.getKey(), e.getValue()));
                continue;
            }
            List<AttrChange> changes = new ArrayList<>();
            Set<String> keys = new java.util.TreeSet<>();
            keys.addAll(e.getValue().keySet());
            keys.addAll(existing.keySet());
            boolean replacement = false;
            for (String key : keys) {
                String before = existing.get(key);
                String after = e.getValue().get(key);
                if (!java.util.Objects.equals(before, after)) {
                    changes.add(new AttrChange(key, before, after));
                    replacement |= forceNewAttrs.contains(key);
                }
            }
            if (!changes.isEmpty()) {
                actions.add(new Update(e.getKey(), List.copyOf(changes), replacement));
            }
        }
        for (String id : current.keySet()) {
            if (!desiredMap.containsKey(id)) {
                actions.add(new Delete(id, String.valueOf(current.get(id))));
            }
        }
        return new Plan(List.copyOf(actions));
    }

    /** 值渲染为状态字符串（保持确定性） */
    public static Map<String, String> renderAttrs(Block block) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Value> e : block.attrs().entrySet()) {
            if (e.getKey().equals(ResourceGraph.DEPENDS_ON)) {
                continue;
            }
            out.put(e.getKey(), render(e.getValue()));
        }
        return out;
    }

    public static String render(Value value) {
        if (value instanceof Str s) {
            return s.s();
        }
        if (value instanceof Num n) {
            return n.d() == Math.rint(n.d()) ? String.valueOf((long) n.d()) : String.valueOf(n.d());
        }
        if (value instanceof Bool b) {
            return String.valueOf(b.b());
        }
        if (value instanceof ListV list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.items().size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(render(list.items().get(i)));
            }
            return sb.append(']').toString();
        }
        if (value instanceof Ref ref) {
            return String.join(".", ref.path());
        }
        throw new IllegalStateException("未知值类型");
    }
}
