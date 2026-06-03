package cn.chyuan.ai.domain.auth.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantScopeVO {

    private String tenantId;
    private String ownerUserId;

    public static TenantScopeVO singleUser(String userId) {
        return TenantScopeVO.builder()
                .tenantId(userId)
                .ownerUserId(userId)
                .build();
    }

    /**
     * 校验租户作用域是否完整可用
     * <p>
     * tenantId 和 ownerUserId 均不为 null 且不为空白字符串时，视为有效作用域。
     * 用于多租户安全校验，防止空字符串或半填充对象绕过权限检查。
     *
     * @return true 表示作用域完整可用
     */
    public boolean isValid() {
        return tenantId != null && !tenantId.isBlank()
                && ownerUserId != null && !ownerUserId.isBlank();
    }
}
