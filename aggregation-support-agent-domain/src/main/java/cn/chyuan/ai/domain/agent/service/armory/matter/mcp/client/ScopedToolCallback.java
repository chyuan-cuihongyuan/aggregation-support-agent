package cn.chyuan.ai.domain.agent.service.armory.matter.mcp.client;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.auth.support.RequestScopeContext;
import cn.chyuan.ai.domain.rag.support.RagSourceCollector;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 租户作用域感知的工具回调装饰器 — 在工具执行前后恢复请求作用域上下文
 * <p>
 * Spring AI 的工具调用有两个入口：
 * <ul>
 *   <li>{@code call(String, ToolContext)} — 携带 ToolContext，可从中恢复作用域</li>
 *   <li>{@code call(String)} — 无 ToolContext，依赖 ThreadLocal 传递作用域</li>
 * </ul>
 * 本装饰器确保两个路径都能正确传递租户作用域，防止跨租户数据泄露。
 */
@Slf4j
public final class ScopedToolCallback implements ToolCallback {

    private final ToolCallback delegate;

    private ScopedToolCallback(ToolCallback delegate) {
        this.delegate = delegate;
    }

    public static ToolCallback wrap(ToolCallback callback) {
        if (callback instanceof ScopedToolCallback) {
            return callback;
        }
        return new ScopedToolCallback(callback);
    }

    public static List<ToolCallback> wrapAll(ToolCallback[] callbacks) {
        if (callbacks == null || callbacks.length == 0) {
            return List.of();
        }
        return Arrays.stream(callbacks).map(ScopedToolCallback::wrap).toList();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        // 无 ToolContext 的调用路径：检查 ThreadLocal 中是否已有作用域
        // 如果 ChatService/Controller 已通过 doOnSubscribe 恢复了作用域，则直接使用
        TenantScopeVO currentScope = RequestScopeContext.get();
        if (currentScope == null) {
            log.warn("工具调用时租户作用域缺失，可能影响多租户隔离。工具: {}, 线程: {}",
                    delegate.getToolDefinition().name(),
                    Thread.currentThread().getName());
        } else {
            log.debug("工具调用时租户作用域正常: tenantId={}, 工具: {}",
                    currentScope.getTenantId(),
                    delegate.getToolDefinition().name());
        }
        try {
            return delegate.call(toolInput);
        } catch (RuntimeException e) {
            return toolError(e);
        }
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        TenantScopeVO tenantScope = resolveTenantScope(toolContext);
        RagSourceCollector.Holder holder = resolveHolder(toolContext);

        TenantScopeVO previousScope = RequestScopeContext.snapshot();
        RagSourceCollector.Holder previousHolder = RagSourceCollector.currentHolder();
        try {
            if (tenantScope != null) {
                RequestScopeContext.attach(tenantScope);
            }
            if (holder != null) {
                RagSourceCollector.attach(holder);
            }
            return delegate.call(toolInput, toolContext);
        } catch (RuntimeException e) {
            return toolError(e);
        } finally {
            RequestScopeContext.attach(previousScope);
            RagSourceCollector.attach(previousHolder);
        }
    }

    private TenantScopeVO resolveTenantScope(ToolContext toolContext) {
        Object value = contextValue(toolContext, RequestScopeContext.TOOL_CONTEXT_TENANT_SCOPE_KEY);
        if (value instanceof TenantScopeVO scope) {
            return scope;
        }
        RagSourceCollector.Holder holder = resolveHolder(toolContext);
        if (holder != null && holder.getTenantScope() != null) {
            return holder.getTenantScope();
        }
        if (value instanceof Map<?, ?> map) {
            Object tenantId = map.get("tenantId");
            Object ownerUserId = map.get("ownerUserId");
            return TenantScopeVO.builder()
                    .tenantId(tenantId == null ? null : String.valueOf(tenantId))
                    .ownerUserId(ownerUserId == null ? null : String.valueOf(ownerUserId))
                    .build();
        }
        return null;
    }

    private RagSourceCollector.Holder resolveHolder(ToolContext toolContext) {
        Object value = contextValue(toolContext, RagSourceCollector.TOOL_CONTEXT_HOLDER_KEY);
        return value instanceof RagSourceCollector.Holder holder ? holder : null;
    }

    private Object contextValue(ToolContext toolContext, String key) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        return toolContext.getContext().get(key);
    }

    private String toolError(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        log.warn("工具调用失败，返回结构化错误给模型: {}", message);
        return "{\"error\":true,\"message\":\"工具调用失败: " + escapeJson(message) + "\"}";
    }

    private String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
