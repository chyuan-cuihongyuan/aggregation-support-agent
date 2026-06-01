package cn.chyuan.ai.trigger.support;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.auth.support.RequestScopeContext;
import jakarta.servlet.http.HttpServletRequest;

public final class TenantScopeSupport {

    private TenantScopeSupport() {
    }

    public static TenantScopeVO currentScope(HttpServletRequest request) {
        String userId = CurrentUserSupport.requireUserIdString(request);
        TenantScopeVO scope = RequestScopeContext.snapshot();
        if (scope == null || isBlank(scope.getTenantId())) {
            return TenantScopeVO.singleUser(userId);
        }
        if (isBlank(scope.getOwnerUserId())) {
            scope.setOwnerUserId(userId);
        }
        return scope;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
