package cn.chyuan.ai.infrastructure.gateway.query;

import cn.chyuan.ai.domain.rag.service.query.RuleBasedQueryRewriter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.AssistantMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 查询改写 LLM 适配单测（工单 0165）
 * <p>
 * 覆盖验收：LLM 异常回退规则版（不抛出）/ ChatModel 缺席走规则版 / LLM 空响应回退 / 正常路径
 */
class LlmQueryRewritePortTest {

    @Test
    void llmExceptionFallsBackToRuleRewriter() {
        // LLM 调用异常：回退规则版结果，不抛出（端口契约）
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("llm down"));
        LlmQueryRewritePort port = new LlmQueryRewritePort(chatModel, new RuleBasedQueryRewriter());

        assertThatCode(() -> {
            String rewritten = port.rewrite("什么是 RAG 的用途啊");
            assertThat(rewritten).isEqualTo("什么是 rag 用途");
        }).doesNotThrowAnyException();
    }

    @Test
    void llmNormalPathReturnsModelOutput() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse response = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        when(generation.getOutput()).thenReturn(new AssistantMessage("RAG 用途说明"));
        when(response.getResult()).thenReturn(generation);
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
        LlmQueryRewritePort port = new LlmQueryRewritePort(chatModel, new RuleBasedQueryRewriter());

        String rewritten = port.rewrite("什么是rag的用途");

        assertThat(rewritten).isEqualTo("RAG 用途说明");
    }

    @Test
    void llmEmptyResponseFallsBackToRuleRewriter() {
        // LLM 返回空串：回退规则版
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse response = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        when(generation.getOutput()).thenReturn(new AssistantMessage("  "));
        when(response.getResult()).thenReturn(generation);
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
        LlmQueryRewritePort port = new LlmQueryRewritePort(chatModel, new RuleBasedQueryRewriter());

        String rewritten = port.rewrite("js 框架推荐");

        assertThat(rewritten).contains("javascript");
    }

    @Test
    void missingChatModelAlwaysUsesRuleRewriter() {
        // ChatModel 缺席（未配置 LLM）：恒走规则版
        LlmQueryRewritePort port = new LlmQueryRewritePort(null, new RuleBasedQueryRewriter());

        String rewritten = port.rewrite("What is the vector database?");

        assertThat(rewritten).isEqualTo("vector database");
    }

    @Test
    void blankQueryShortCircuitsWithoutLlmCall() {
        // 空串保真：不经 LLM 直接返回
        ChatModel chatModel = mock(ChatModel.class);
        LlmQueryRewritePort port = new LlmQueryRewritePort(chatModel, new RuleBasedQueryRewriter());

        assertThat(port.rewrite("")).isEmpty();
        assertThat(port.rewrite(null)).isNull();
    }
}
