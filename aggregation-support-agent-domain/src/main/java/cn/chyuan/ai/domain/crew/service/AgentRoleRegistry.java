package cn.chyuan.ai.domain.crew.service;

import cn.chyuan.ai.domain.crew.model.AgentRole;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 角色注册表（工单 0213 AC1）— role 名唯一；注册重复即拒绝。
 *
 * @author chyuan
 */
public class AgentRoleRegistry {

    private final ConcurrentHashMap<String, AgentRole> roles = new ConcurrentHashMap<>();

    public void register(AgentRole role) {
        roles.compute(role.role(), (name, existing) -> {
            if (existing != null) {
                throw new IllegalArgumentException("角色已注册: " + name);
            }
            return role;
        });
    }

    public AgentRole get(String role) {
        return role == null ? null : roles.get(role);
    }

    public Set<String> names() {
        return Set.copyOf(roles.keySet());
    }

    public int size() {
        return roles.size();
    }
}
