package cn.chyuan.ai.domain.agent.service.armory.node.workflow;

import cn.chyuan.ai.domain.agent.service.armory.factory.AgentEnhancementContext;
import cn.chyuan.ai.domain.agent.service.armory.matter.tools.ExitLoopTool;
import com.google.adk.agents.CallbackContext;
import com.google.adk.events.EventActions;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Agentic Workflow 增强器 —— 为工作流中的子 agent 注入"条件退出"与"跨迭代记忆"能力。
 *
 * <p>M1（动态 Replan）与 M2（Reflexion 反思）共用本类。
 *
 * <h3>设计依据（反编译 ADK 0.5.0 字节码确认，非文档推测）</h3>
 * <ul>
 *   <li>{@code ExitLoopTool.exitLoop(ToolContext)} 实现：
 *       {@code toolContext.setActions(actions.toBuilder().escalate(true).build())}，
 *       LoopAgent.runAsyncImpl 的 {@code takeUntil(hasEscalateAction)} 检测到后提前退出循环。</li>
 *   <li>{@code CallbackContext.eventActions()} 返回可变 {@link EventActions}，
 *       其 {@code setEscalate(boolean)} 可在 callback 中代码强制触发退出。</li>
 *   <li>{@code CallbackContext.state()}（Session state）是 {@code ConcurrentMap}，
 *       整个 LoopAgent 生命周期共享，Reflexion 借此累积跨迭代反思。</li>
 * </ul>
 *
 * @author chyuan
 * @since 2026-06-13
 */
@Slf4j
public final class AgenticWorkflowEnhancer {

    /** 评估者输出中表示"需要返工"的关键字（命中则不退出循环） */
    private static final String NEEDS_REPLAN_KEYWORD = "NEEDS_REPLAN";
    private static final String NEEDS_IMPROVE_KEYWORD = "NEEDS_IMPROVEMENT";

    private AgenticWorkflowEnhancer() {
    }

    /**
     * 给评估者（Evaluator/Critic）挂"条件退出"能力 —— 双保险退出机制（精确版本，支持正则匹配）。
     *
     * <ol>
     *   <li>挂 {@link ExitLoopTool}：LLM 在 instruction 引导下调用 exit_loop 工具触发 escalate（ADK 官方机制）</li>
     *   <li>afterModelCallback 强门控：解析响应文本，命中 passPattern 正则且不含失败关键字时，
     *       代码强制 {@code setEscalate(true)}，不依赖 LLM 自觉调用</li>
     * </ol>
     *
     * @param ctx            评估者 agent 的增强上下文
     * @param passPattern    视为"评估通过"的正则模式，命中即强制退出循环。
     *                       例：{@code "verdict"\s*:\s*"SUFFICIENT"} 精确匹配 JSON 字段值，
     *                       避免 {@code INSUFFICIENT} 子串误命中 {@code SUFFICIENT}。
     *                       为 null 时不启用强门控，仅挂 ExitLoopTool。
     * @param failKeywordsCsv 逗号分隔的失败关键字（命中则不触发退出），默认 "INSUFFICIENT,NEEDS_REPLAN,NEEDS_IMPROVEMENT"
     */
    public static void attachExitLoopGate(AgentEnhancementContext ctx, String passPattern, String failKeywordsCsv) {
        // 1. 追加 ExitLoopTool（ctx.addTool 内部读现有 toolsUnion 合并，避免 setter 覆盖丢失已有工具 #7）
        //    使用项目自定义 ExitLoopTool（matter.tools.ExitLoopTool），与 AgentNode 的 exitLoopEnabled 机制统一
        ctx.addTool(FunctionTool.create(ExitLoopTool.class, "exitLoop"));

        // 2. 解析失败关键字列表
        final java.util.List<String> failKeywords = java.util.Arrays.stream(
                (failKeywordsCsv == null ? "INSUFFICIENT,NEEDS_REPLAN,NEEDS_IMPROVEMENT" : failKeywordsCsv).split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(String::toUpperCase).toList();

        // 3. 预编译正则（延迟到 lambda 内部，避免 effectively final 问题）
        final String finalPattern = passPattern;

        // 4. afterModelCallback 强门控 —— 精确正则匹配 + 失败关键字排除
        ctx.afterModelCallbackSync((callbackContext, llmResponse) -> {
            String text = extractText(llmResponse);
            if (text == null) {
                return Optional.empty();
            }

            String upper = text.toUpperCase();
            boolean needsRework = failKeywords.stream().anyMatch(upper::contains);
            boolean passed = false;
            if (finalPattern != null && !finalPattern.isBlank()) {
                try {
                    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(finalPattern, java.util.regex.Pattern.CASE_INSENSITIVE);
                    passed = pattern.matcher(text).find();
                } catch (Exception e) {
                    log.error("【强门控】正则匹配失败: {}", finalPattern, e);
                }
            }

            if (passed && !needsRework) {
                EventActions actions = callbackContext.eventActions();
                if (actions != null) {
                    actions.setEscalate(true);
                    log.info("【强门控】评估通过[match={}, text={}]", finalPattern, text.substring(0, Math.min(100, text.length())));
                } else {
                    // eventActions 为 null 通常意味着 agent 不在 LoopAgent 上下文中（escalate 只对 LoopAgent 生效）
                    log.warn("【强门控失效】eventActions 为 null，agent 可能不在 LoopAgent 中，强门控无法触发，依赖 ExitLoopTool/LLM 兜底");
                }
            }
            return Optional.empty();
        });

        log.info("已为评估者追加 ExitLoopTool + 强门控(passPattern={}, failKeywords={})", passPattern, failKeywordsCsv);
    }

    /**
     * 给评估者（Evaluator/Critic）挂"条件退出"能力 —— 双保险退出机制（向后兼容版本）。
     *
     * <p>委托到 3 参数版本，将 passKeyword 包装为正则 {@code \QpassKeyword\E}（精确字面匹配）。
     *
     * @param ctx        评估者 agent 的 Builder
     * @param passKeyword 视为"评估通过"的关键字（字面匹配），命中即强制退出循环
     */
    public static void attachExitLoopGate(AgentEnhancementContext ctx, String passKeyword) {
        String pattern = (passKeyword != null && !passKeyword.isBlank())
                ? "\\Q" + java.util.regex.Pattern.quote(passKeyword) + "\\E"
                : null;
        attachExitLoopGate(ctx, pattern, null);
    }

    /**
     * 给反思改进者（Reflector）挂"写入记忆"能力 —— 每轮结束后把本轮反思追加到 session state。
     *
     * <p>读取 Reflector 的 outputKey 对应 state（ADK 在 agent 完成时已写入 outputKey），
     * 累积到 reflections 列表，供下一轮 Actor 读取。
     *
     * @param builder   Reflector 的 Builder
     * @param outputKey Reflector 的 outputKey（其输出已写入 state[outputKey]）
     * @param stateKey  反思累积的 state key（如 "reflections:rag"）
     */
    public static void attachReflectionWriter(AgentEnhancementContext ctx, String outputKey, String stateKey) {
        ctx.afterAgentCallbackSync(callbackContext -> {
            Object output = callbackContext.state().get(outputKey);
            if (!(output instanceof String reflection) || reflection.isBlank()) {
                return Optional.empty();
            }
            List<String> reflections = getOrCreateReflectionList(callbackContext, stateKey);
            reflections.add(reflection);
            callbackContext.state().put(stateKey, reflections);
            log.info("【Reflexion】追加第 {} 轮反思到 state[{}]（来源 outputKey={}）", reflections.size(), stateKey, outputKey);
            return Optional.empty();
        });
    }

    /**
     * 给执行者（Actor）挂"读取记忆"能力 —— 每轮模型调用前把累积反思注入 instruction。
     *
     * <p>通过 beforeModelCallback 修改 LlmRequest 的指令，把历史反思作为"避免重复错误"的上下文拼入。
     *
     * @param builder  Actor 的 Builder
     * @param stateKey 反思累积的 state key
     */
    public static void attachReflectionReader(AgentEnhancementContext ctx, String stateKey) {
        ctx.beforeModelCallbackSync((callbackContext, llmRequestBuilder) -> {
            Object val = callbackContext.state().get(stateKey);
            if (val instanceof List<?> list && !list.isEmpty()) {
                StringBuilder sb = new StringBuilder("\n\n## 历史反思教训（避免重复同样的错误，请据此改进本轮执行）\n");
                for (int i = 0; i < list.size(); i++) {
                    sb.append("第").append(i + 1).append("轮反思：").append(list.get(i)).append("\n");
                }
                // 把反思拼接到指令末尾（appendInstructions 接收 List<String>，反编译 ADK 0.5.0 确认）
                llmRequestBuilder.appendInstructions(List.of(sb.toString()));
                log.info("【Reflexion】向 Actor 注入 {} 轮历史反思到 state[{}]", list.size(), stateKey);
            }
            return Optional.empty();
        });
    }

    /** 从 session state 取或创建反思列表（CopyOnWriteArrayList 保证并发安全） */
    @SuppressWarnings("unchecked")
    private static List<String> getOrCreateReflectionList(CallbackContext callbackContext, String stateKey) {
        Object existing = callbackContext.state().get(stateKey);
        if (existing instanceof List<?> list && !list.isEmpty()) {
            // 已存在且非空，复用（保证跨迭代累积）
            return (List<String>) list;
        }
        return new CopyOnWriteArrayList<>();
    }

    /** 从 LlmResponse 提取纯文本（合并所有 Part 的 text） */
    static String extractText(LlmResponse llmResponse) {
        if (llmResponse == null || llmResponse.content().isEmpty()) {
            return null;
        }
        return llmResponse.content()
                .flatMap(Content::parts)
                .map(parts -> parts.stream()
                        .map(Part::text)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .reduce("", String::concat))
                .orElse(null);
    }

}
