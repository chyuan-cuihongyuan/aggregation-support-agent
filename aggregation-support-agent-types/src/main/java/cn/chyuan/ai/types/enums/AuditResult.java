package cn.chyuan.ai.types.enums;

import lombok.Getter;

/**
 * 审计结果枚举 — 与 audit_log.result 列对齐
 */
@Getter
public enum AuditResult {

    SUCCESS("SUCCESS"),
    FAILURE("FAILURE"),
    ;

    private final String code;

    AuditResult(String code) {
        this.code = code;
    }
}
