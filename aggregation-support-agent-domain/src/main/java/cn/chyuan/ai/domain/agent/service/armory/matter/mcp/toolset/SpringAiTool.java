package cn.chyuan.ai.domain.agent.service.armory.matter.mcp.toolset;

import com.alibaba.fastjson.JSON;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Single;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 将 Spring AI 的 {@link ToolCallback} 适配为 ADK 的 {@link BaseTool}。
 * <p>
 * ADK 的工具执行链路要求工具以 {@code BaseTool} 形式注册到 {@code LlmRequest.tools()}，
 * {@link #declaration()} 提供给 LLM 的函数声明，{@link #runAsync(Map, ToolContext)} 负责实际执行。
 * 本类把这两步桥接到 Spring AI 的 {@link ToolCallback}：
 * <ul>
 *   <li>{@code declaration()} 由 {@code ToolCallback.getToolDefinition()} 的 name/description/inputSchema 构建</li>
 *   <li>{@code runAsync()} 把参数序列化为 JSON 后调用 {@code ToolCallback.call(jsonArgs)}</li>
 * </ul>
 * 注意：传入的 {@code delegate} 通常已被 {@code ScopedToolCallback} 包装，
 * 执行时会自动恢复租户作用域 ThreadLocal，无需在此重复处理。
 *
 * @author chyuan
 * 2026/7/1
 */
@Slf4j
public class SpringAiTool extends BaseTool {

    private final ToolCallback delegate;

    SpringAiTool(ToolCallback delegate) {
        super(extractName(delegate), extractDescription(delegate));
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
    }

    private static String extractName(ToolCallback delegate) {
        return delegate.getToolDefinition().name();
    }

    private static String extractDescription(ToolCallback delegate) {
        String description = delegate.getToolDefinition().description();
        return description == null || description.isBlank() ? extractName(delegate) : description;
    }

    /**
     * 构建提供给 LLM 的函数声明，参数 Schema 由 Spring AI 的 inputSchema(JSON 字符串)转换而来。
     * 解析失败时降级返回 empty，由 ADK 用 name/description 兜底。
     */
    @Override
    public Optional<FunctionDeclaration> declaration() {
        ToolDefinition definition = delegate.getToolDefinition();
        FunctionDeclaration.Builder builder = FunctionDeclaration.builder()
                .name(definition.name())
                .description(extractDescription(delegate));

        String inputSchema = definition.inputSchema();
        if (inputSchema != null && !inputSchema.isBlank()) {
            try {
                builder.parameters(Schema.fromJson(inputSchema));
            } catch (Exception e) {
                // inputSchema 解析失败不致命：LLM 仍可依据 name/description 调用，
                // 参数透传给 ToolCallback 时由其自行解析
                log.warn("SpringAiTool [{}] 解析 inputSchema 失败，降级为无参数声明: {}",
                        definition.name(), e.getMessage());
            }
        }

        return Optional.of(builder.build());
    }

    /**
     * 执行工具：把 ADK 传入的参数 Map 序列化为 JSON，委托给 Spring AI ToolCallback。
     * ToolCallback.call 是阻塞调用，用 Single.fromCallable 包装。
     */
    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
        return Single.fromCallable(() -> {
            String toolInput = args == null || args.isEmpty() ? "{}" : JSON.toJSONString(args);
            String toolName = delegate.getToolDefinition().name();
            try {
                String output = delegate.call(toolInput);
                log.debug("SpringAiTool [{}] 执行完成，入参: {}，出参: {}", toolName, toolInput, truncate(output));
                return parseOutput(output);
            } catch (RuntimeException e) {
                log.warn("SpringAiTool [{}] 执行异常: {}", toolName, e.getMessage());
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("error", true);
                error.put("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                return error;
            }
        });
    }

    /**
     * ToolCallback 返回的字符串可能是 JSON 对象，尽量解析为 Map 以符合 ADK FunctionResponse 约定；
     * 解析失败时包装为 {"result": output}，保证不丢失原始结果。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseOutput(String output) {
        if (output == null || output.isBlank()) {
            return Map.of("result", "");
        }
        try {
            Object parsed = JSON.parse(output);
            if (parsed instanceof Map) {
                return (Map<String, Object>) parsed;
            }
        } catch (Exception ignored) {
            // 非 JSON 字符串，走兜底包装
        }
        return Map.of("result", output);
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 500 ? text.substring(0, 500) + "..." : text;
    }
}
