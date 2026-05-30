package cn.chyuan.ai.domain.auth.support;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;

public final class RequestScopeContext {

    private static final ThreadLocal<TenantScopeVO> CURRENT_SCOPE = new ThreadLocal<>();

    private RequestScopeContext() {
    }

    public static void set(TenantScopeVO scope) {
        CURRENT_SCOPE.set(scope);
    }

    public static TenantScopeVO get() {
        return CURRENT_SCOPE.get();
    }

    public static void clear() {
        CURRENT_SCOPE.remove();
    }
}
