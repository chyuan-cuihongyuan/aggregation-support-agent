package cn.chyuan.ai.infrastructure.gateway.query;

import cn.chyuan.ai.domain.rag.service.query.IQueryOptimizationService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询优化服务实现 — 使用LLM对用户query做加工
 */
@Slf4j
@Service
public class QueryOptimizationService implements IQueryOptimizationService {

    @Autowired(required = false)
    private ChatModel chatModel;

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
            String rewritten = chatModel.call(new Prompt(new UserMessage(prompt)))
                    .getResult().getOutput().getText();
            log.info("Query改写完成: original={}, rewritten={}", originalQuery, rewritten);
            return rewritten.trim();
        } catch (Exception e) {
            log.warn("Query改写失败，返回原始查询: {}", e.getMessage());
            return originalQuery;
        }
    }

    @Override
    public List<String> expandQuery(String originalQuery, int count) {
        log.info("Multi-Query扩展: originalQuery={}, count={}", originalQuery, count);

        if (chatModel == null) {
            log.warn("ChatModel未配置，返回原始查询");
            List<String> fallback = new ArrayList<>();
            fallback.add(originalQuery);
            return fallback;
        }

        String prompt = """
                请将以下问题扩展为%d个不同角度的问法。
                
                扩展规则：
                1. 每个问法保持原意但表达方式不同
                2. 可以从不同角度提问（如：操作步骤、原理说明、常见问题等）
                3. 包含口语化和书面语两种风格
                4. 原始问题必须保留在结果中
                5. 返回JSON数组格式，如：["问题1", "问题2", "问题3"]
                
                原始问题：%s
                
                请返回JSON数组：
                """.formatted(count, originalQuery);

        try {
            String result = chatModel.call(new Prompt(new UserMessage(prompt)))
                    .getResult().getOutput().getText();

            // 提取JSON数组
            JSONArray jsonArray = JSON.parseArray(extractJson(result));
            List<String> queries = new ArrayList<>(jsonArray.toJavaList(String.class));

            // 确保原始问题在列表中
            if (!queries.contains(originalQuery)) {
                queries.add(0, originalQuery);
            }

            log.info("Multi-Query扩展完成: queries={}", queries);
            return queries;
        } catch (Exception e) {
            log.warn("Multi-Query扩展失败，返回原始查询: {}", e.getMessage());
            List<String> fallback = new ArrayList<>();
            fallback.add(originalQuery);
            return fallback;
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
            String hypotheticalDoc = chatModel.call(new Prompt(new UserMessage(prompt)))
                    .getResult().getOutput().getText();
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
            String stepBackQuery = chatModel.call(new Prompt(new UserMessage(prompt)))
                    .getResult().getOutput().getText();
            log.info("Step-back生成完成: stepBackQuery={}", stepBackQuery);
            return stepBackQuery.trim();
        } catch (Exception e) {
            log.warn("Step-back生成失败: {}", e.getMessage());
            return specificQuery;
        }
    }

    /**
     * 从字符串中提取JSON数组
     */
    private String extractJson(String text) {
        // 尝试找到JSON数组
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

}
