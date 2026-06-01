package cn.chyuan.ai.domain.auth.support;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;

public final class RequestScopeContext {

    public static final String TOOL_CONTEXT_TENANT_SCOPE_KEY =
            RequestScopeContext.class.getName() + ".tenantScope";

    private static final ThreadLocal<TenantScopeVO> CURRENT_SCOPE = new InheritableThreadLocal<>();

    private RequestScopeContext() {
    }

    public static void set(TenantScopeVO scope) {
        if (scope == null) {
            clear();
            return;
        }
        CURRENT_SCOPE.set(copyOf(scope));
    }

    public static void attach(TenantScopeVO scope) {
        if (scope == null) {
            clear();
            return;
        }
        set(scope);
    }

    public static TenantScopeVO get() {
        return CURRENT_SCOPE.get();
    }

    public static TenantScopeVO snapshot() {
        return copyOf(CURRENT_SCOPE.get());
    }

    public static TenantScopeVO copyOf(TenantScopeVO scope) {
        if (scope == null) {
            return null;
        }
        return TenantScopeVO.builder()
                .tenantId(scope.getTenantId())
                .ownerUserId(scope.getOwnerUserId())
                .build();
    }

    public static void clear() {
        CURRENT_SCOPE.remove();
    }
}
