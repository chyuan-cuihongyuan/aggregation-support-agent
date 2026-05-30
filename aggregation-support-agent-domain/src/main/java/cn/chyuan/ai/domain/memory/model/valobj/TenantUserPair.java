package cn.chyuan.ai.domain.memory.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 租户-用户对值对象。
 * <p>
 * 用于记忆整合任务中遍历所有有效的租户-用户组合。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantUserPair {

    /** 租户ID */
    private String tenantId;

    /** 用户ID */
    private String userId;
}
