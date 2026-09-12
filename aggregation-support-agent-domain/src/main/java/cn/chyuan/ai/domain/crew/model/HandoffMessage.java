package cn.chyuan.ai.domain.crew.model;

/**
 * 交接消息（工单 0216 AC4，借鉴 AutoGen handoff）—
 * Agent 间统一交接结构：from/to（必须为注册角色）+ task 任务 + summary 结论摘要 +
 * context 引用（只放键引用不放大文本，配合 ContextTrimmer 预算裁剪）。
 *
 * @author chyuan
 */
public record HandoffMessage(String fromRole, String toRole, String task, String summary,
        java.util.List<String> contextKeys) {

    public HandoffMessage {
        if (fromRole == null || fromRole.isBlank() || toRole == null || toRole.isBlank()) {
            throw new IllegalArgumentException("交接双方角色不能为空");
        }
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("交接任务不能为空");
        }
        summary = summary == null ? "" : summary;
        contextKeys = contextKeys == null ? java.util.List.of() : java.util.List.copyOf(contextKeys);
    }

    /** 校验双方均为注册角色（registry 注入校验） */
    public void assertRegistered(cn.chyuan.ai.domain.crew.service.AgentRoleRegistry registry) {
        for (String role : new String[]{fromRole, toRole}) {
            if (registry.get(role) == null) {
                throw new IllegalArgumentException("交接角色未注册: " + role);
            }
        }
    }
}
