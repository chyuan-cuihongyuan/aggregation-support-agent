package cn.chyuan.ai.infrastructure.gateway.query;

import cn.chyuan.ai.domain.rag.adapter.port.IQueryRewritePort;
import cn.chyuan.ai.domain.rag.service.query.RuleBasedQueryRewriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * 查询改写 LLM 适配（工单 0165，W3）— LLM 改写 + 异常回退规则版
 * <p>
 * rag.rewrite-enabled=true 且 rag.rewrite-provider=llm 时装配。
 * 端口契约：LLM 调用任何异常（网络/超时/解析）或返回空 → 回退规则版改写结果，
 * 不抛出、不返回空。ChatModel 缺席（未配置 LLM）时恒走规则版。
 */
@Slf4j
public class LlmQueryRewritePort implements IQueryRewritePort {

    private static final String PROMPT_TEMPLATE = """
            你是检索查询改写助手。请去除口语与停用词，把用户问题改写为简洁的检索查询，\
            只返回改写后的查询本身，不要解释。

            用户问题：%s
            """;

    /** LLM 专用模型（可缺席：未配置 LLM 时恒走规则版） */
    private final ChatModel chatModel;

    /** 规则版兜底（W 簇裁定：LLM 端口必带规则兜底实现） */
    private final RuleBasedQueryRewriter fallback;

    public LlmQueryRewritePort(ChatModel chatModel, RuleBasedQueryRewriter fallback) {
        this.chatModel = chatModel;
        this.fallback = fallback;
    }

    @Override
    public String rewrite(String query) {
        // 空串 / null 保真，不经 LLM
        if (query == null || query.isBlank()) {
            return query;
        }
        if (chatModel == null) {
            log.debug("ChatModel 未配置，查询改写走规则版");
            return fallback.rewrite(query);
        }
        try {
            String rewritten = chatModel.call(new Prompt(new UserMessage(
                    PROMPT_TEMPLATE.formatted(query))))
                    .getResult().getOutput().getText();
            if (rewritten == null || rewritten.isBlank()) {
                // LLM 空响应：回退规则版
                log.info("LLM 查询改写返回空，回退规则版");
                return fallback.rewrite(query);
            }
            return rewritten.trim();
        } catch (Exception e) {
            // LLM 异常：回退规则版，不抛出（端口契约）
            log.warn("LLM 查询改写失败，回退规则版: {}", e.getMessage());
            return fallback.rewrite(query);
        }
    }
}
