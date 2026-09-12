package cn.chyuan.ai.infrastructure.gateway.query;

import cn.chyuan.ai.domain.rag.service.query.IQueryOptimizationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 查询优化服务实现 — 使用LLM对用户query做加工
 */
@Slf4j
@Service
public class QueryOptimizationService implements IQueryOptimizationService {

    @Autowired(required = false)
    private ChatModel chatModel;

    @Autowired
    private LlmOutputGuard llmOutputGuard;

    @Override
    public String rewriteQuery(String originalQuery, List<String> chatHistory) {
        log.info("Query改写: originalQuery={}", originalQuery);

        if (chatModel == null) {
            log.warn("ChatModel未配置，返回原始查询");
            return originalQuery;
        }

        String historyStr = chatHistory != null ? String.join("\n", chatHistory) : "无";

        String prompt = """
                你是一个查询改写专家。请将用户的问题改写为更正式、更精准的书面表达。

                改写规则：
                1. 保持原意不变
                2. 去除口语化表达
                3. 补充缺失的指代信息（如"它"、"这个"等）
                4. 将模糊表述具体化
                5. 只返回改写后的查询，不要解释

                对话历史：
                %s

                用户问题：%s

                改写后的查询：
                """.formatted(historyStr, originalQuery);

        try {
            // 输出守卫（SELFLOOP2 loop-229）：空/超长/解释性输出重试，全败回退原查询
            String rewritten = llmOutputGuard.callUntilValid(
                            () -> chatModel.call(new Prompt(new UserMessage(prompt)))
                                    .getResult().getOutput().getText(),
                            LlmOutputGuard.NON_BLANK_MAX_500)
                    .orElse(originalQuery);
            log.info("Query改写完成: original={}, rewritten={}", originalQuery, rewritten);
            return rewritten;
        } catch (Exception e) {
            log.warn("Query改写失败，返回原始查询: {}", e.getMessage());
            return originalQuery;
        }
    }

    @Override
    public String generateHypotheticalDocument(String query) {
        log.info("HyDE生成假设文档: query={}", query);

        if (chatModel == null) {
            log.warn("ChatModel未配置，返回原始查询");
            return query;
        }

        String prompt = """
                请根据以下问题，生成一段假设的文档内容。
                这段文档应该像是从知识库中检索出来的，能够回答这个问题。

                要求：
                1. 使用陈述性语言，而非提问
                2. 包含具体的技术细节或操作步骤
                3. 长度适中（200-400字）
                4. 不要提及"假设"、"可能"等不确定性词汇

                问题：%s

                假设的文档内容：
                """.formatted(query);

        try {
            String hypotheticalDoc = llmOutputGuard.callUntilValid(
                            () -> chatModel.call(new Prompt(new UserMessage(prompt)))
                                    .getResult().getOutput().getText(),
                            LlmOutputGuard.NON_BLANK_MAX_500)
                    .orElse(query);
            log.info("HyDE生成完成: length={}", hypotheticalDoc.length());
            return hypotheticalDoc.trim();
        } catch (Exception e) {
            log.warn("HyDE生成失败: {}", e.getMessage());
            return query;
        }
    }

    @Override
    public String generateStepBackQuery(String specificQuery) {
        log.info("Step-back Prompting: specificQuery={}", specificQuery);

        if (chatModel == null) {
            log.warn("ChatModel未配置，返回原始查询");
            return specificQuery;
        }

        String prompt = """
                请将以下具体问题抽象为一个更通用的背景问题。
                背景问题应该能够检索到回答原问题所需的基础知识。

                示例：
                - 具体问题："为什么transformer attention要除以sqrt(d_k)"
                - 背景问题："attention机制的数学原理"

                - 具体问题："iPhone 15 Pro Max的电池容量是多少"
                - 背景问题："iPhone电池规格参数"

                具体问题：%s

                背景问题（只返回问题，不要解释）：
                """.formatted(specificQuery);

        try {
            String stepBackQuery = llmOutputGuard.callUntilValid(
                            () -> chatModel.call(new Prompt(new UserMessage(prompt)))
                                    .getResult().getOutput().getText(),
                            LlmOutputGuard.NON_BLANK_MAX_500)
                    .orElse(specificQuery);
            log.info("Step-back生成完成: stepBackQuery={}", stepBackQuery);
            return stepBackQuery;
        } catch (Exception e) {
            log.warn("Step-back生成失败: {}", e.getMessage());
            return specificQuery;
        }
    }

}
