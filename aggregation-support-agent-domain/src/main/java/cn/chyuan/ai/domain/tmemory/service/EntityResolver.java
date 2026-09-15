package cn.chyuan.ai.domain.tmemory.service;

import cn.chyuan.ai.domain.tmemory.model.valobj.MergePlanVO;
import cn.chyuan.ai.domain.tmemory.model.valobj.MemoryEdgeVO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 实体消解与归并（工单 0363 AS2，graphiti entity resolution + mem0）。
 * 名称归一键 + 类型一致约束 + 可配置别名表 → 归并组与边端点重定向；重定向含自映射保证幂等。
 */
public class EntityResolver {

    /** 别名表：别名→规范名（可空） */
    private final Map<String, String> aliasTable;

    public EntityResolver(Map<String, String> aliasTable) {
        this.aliasTable = aliasTable == null ? Map.of() : aliasTable;
    }

    /**
     * 实体名×类型 → 归并计划（同归一键且类型一致才合并；类型冲突保持分离）。
     */
    public MergePlanVO resolve(List<Entity> entities) {
        Map<String, MergePlanVO.Group> groupByKey = new LinkedHashMap<>();
        Map<String, String> redirect = new LinkedHashMap<>();
        for (Entity entity : entities == null ? List.<Entity>of() : entities) {
            if (entity == null || isBlank(entity.name)) {
                continue;
            }
            String canonicalName = aliasTable.getOrDefault(entity.name, entity.name);
            String key = normalizeKey(canonicalName);
            MergePlanVO.Group group = groupByKey.get(key);
            if (group == null) {
                group = MergePlanVO.Group.builder()
                        .canonicalName(canonicalName)
                        .type(entity.type)
                        .members(new ArrayList<>(List.of(entity.name)))
                        .reason(entity.name.equals(canonicalName) ? "norm-key" : "alias")
                        .build();
                groupByKey.put(key, group);
            } else if (group.getType() != null && group.getType().equals(entity.type)) {
                group.getMembers().add(entity.name);
            } else {
                // 类型冲突：不合并，独立成组（key 加类型后缀避免覆盖）
                String splitKey = key + "|" + entity.type;
                groupByKey.computeIfAbsent(splitKey,
                        k -> MergePlanVO.Group.builder()
                                .canonicalName(canonicalName)
                                .type(entity.type)
                                .members(new ArrayList<>(List.of(entity.name)))
                                .reason("type-conflict-kept")
                                .build());
                redirect.put(entity.name, entity.name);
                continue;
            }
            // 成员统一重定向到组规范名（含首成员自映射，保证幂等）
            redirect.put(entity.name, group.getCanonicalName());
        }
        // 规范名自映射（幂等关键）
        for (MergePlanVO.Group group : groupByKey.values()) {
            redirect.putIfAbsent(group.getCanonicalName(), group.getCanonicalName());
        }
        return MergePlanVO.builder().groups(List.copyOf(groupByKey.values())).redirect(Map.copyOf(redirect)).build();
    }

    /**
     * 按计划重定向边端点（副本返回；重复应用幂等）。
     */
    public List<MemoryEdgeVO> apply(List<MemoryEdgeVO> edges, MergePlanVO plan) {
        if (edges == null || plan == null || plan.getRedirect() == null) {
            return edges;
        }
        List<MemoryEdgeVO> out = new ArrayList<>(edges.size());
        for (MemoryEdgeVO edge : edges) {
            out.add(edge.toBuilder()
                    .subject(plan.getRedirect().getOrDefault(edge.getSubject(), edge.getSubject()))
                    .object(plan.getRedirect().getOrDefault(edge.getObject(), edge.getObject()))
                    .build());
        }
        return out;
    }

    /** 名称归一键：去全部空白 + 小写 + 全角折半为半角 */
    static String normalizeKey(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (char ch : name.toCharArray()) {
            if (Character.isWhitespace(ch)) {
                continue;
            }
            if (ch >= 0xFF01 && ch <= 0xFF5E) {
                sb.append((char) (ch - 0xFEE0));
            } else if (ch == 0x3000) {
                sb.append(' ');
            } else {
                sb.append(Character.toLowerCase(ch));
            }
        }
        return sb.toString();
    }

    /** 别名表环检测：别名链不得成环（构建期防御） */
    public void validateNoAliasCycle() {
        for (String alias : aliasTable.keySet()) {
            Set<String> visited = new LinkedHashSet<>();
            String current = alias;
            while (aliasTable.containsKey(current)) {
                if (!visited.add(current)) {
                    throw new IllegalStateException("别名表存在环: " + visited);
                }
                current = aliasTable.get(current);
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 实体入参（名称+类型） */
    public record Entity(String name, String type) {
    }
}
