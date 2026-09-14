package cn.chyuan.ai.domain.browser.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 恢复决策值对象（AQ8：错误类型 → 恢复级别）
 */
@Getter
public final class RecoveryDecision {

    /** 恢复级别常量 */
    public static final String RETRY = "RETRY";
    public static final String SKIP = "SKIP";
    public static final String RELOCATE = "RELOCATE";
    public static final String ABORT = "ABORT";

    private final String level;
    private final String reason;

    private RecoveryDecision(String level, String reason) {
        this.level = level;
        this.reason = reason;
    }

    public static RecoveryDecision of(String level, String reason) {
        return new RecoveryDecision(level, reason);
    }
}
