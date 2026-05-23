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
}
