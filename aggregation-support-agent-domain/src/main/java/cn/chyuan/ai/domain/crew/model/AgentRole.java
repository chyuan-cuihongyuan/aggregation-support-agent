package cn.chyuan.ai.domain.crew.model;

import java.util.Set;

/**
 * 角色化 Agent 定义（工单 0213 AC1，借鉴 CrewAI Agent）—
 * role（角色名，注册表唯一）/ goal（目标）/ backstory（背景人设）+ 工具白名单；
 * 越权校验：调用不在白名单内的工具一律拒绝。domain 纯值对象。
 *
 * @author chyuan
 */
public record AgentRole(String role, String goal, String backstory, Set<String> toolWhitelist) {

    public AgentRole {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role 不能为空");
        }
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal 不能为空");
        }
        backstory = backstory == null ? "" : backstory;
        toolWhitelist = toolWhitelist == null ? Set.of() : Set.copyOf(toolWhitelist);
    }

    /** 工具越权校验：白名单为空 = 不允许任何工具；命中返回 true */
    public boolean toolAllowed(String toolName) {
        return toolName != null && toolWhitelist.contains(toolName);
    }

    /** 越权即拒绝（统一错误消息供测试与日志） */
    public void assertToolAllowed(String toolName) {
        if (!toolAllowed(toolName)) {
            throw new IllegalArgumentException(
                    "角色 " + role + " 无权使用工具: " + toolName + "（白名单 " + toolWhitelist + "）");
        }
    }

    /** 组装为提示词人设段（顺序流程/层级流程共用的角色提示） */
    public String personaPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 ").append(role).append("。目标：").append(goal).append("。");
        if (!backstory.isBlank()) {
            sb.append("背景：").append(backstory);
        }
        if (!toolWhitelist.isEmpty()) {
            sb.append("可用工具：").append(String.join("、", toolWhitelist)).append("。");
        }
        return sb.toString();
    }
}
