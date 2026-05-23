package cn.chyuan.ai.types.enums;

import lombok.Getter;

/**
 * 审计动作枚举 — 与 audit_log.action 列对齐
 */
@Getter
public enum AuditAction {

    LOGIN("LOGIN", "登录"),
    LOGOUT("LOGOUT", "登出"),
    REGISTER("REGISTER", "注册"),
    UPLOAD_DOC("UPLOAD_DOC", "上传文档"),
    DELETE_DOC("DELETE_DOC", "删除文档"),
    CHANGE_ROLE("CHANGE_ROLE", "修改用户角色"),
    CHANGE_STATUS("CHANGE_STATUS", "修改用户状态"),
    RAG_QUERY("RAG_QUERY", "RAG 检索"),
    ;

    private final String code;
    private final String desc;

    AuditAction(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
