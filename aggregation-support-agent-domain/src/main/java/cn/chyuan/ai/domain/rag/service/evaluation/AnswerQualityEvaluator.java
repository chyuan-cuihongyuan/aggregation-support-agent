package cn.chyuan.ai.domain.rag.service.evaluation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 答案质量评估服务 — 检测幻觉、验证答案与检索内容的一致性
 * <p>
 * 评估维度：
 * <ul>
 *   <li>忠实度（Faithfulness）：答案是否基于检索内容</li>
 *   <li>相关度（Relevancy）：答案是否回答了用户问题</li>
 *   <li>引用准确性：答案中的信息是否有来源支撑</li>
 * </ul>
 */
@Slf4j
@Service
public class AnswerQualityEvaluator {

    @Autowired(required = false)
    private ChatModel chatModel;

    private static final Pattern SCORE_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    /**
     * 评估答案质量
     *
     * @param query           用户查询
     * @param answer          LLM生成的答案
     * @param retrievedChunks 检索到的chunk内容
     * @return 评估结果
     */
    public EvaluationResult evaluate(String query, String answer, List<String> retrievedChunks) {
        log.info("开始答案质量评估: query={}", query);

        // 合并检索内容
        String context = String.join("\n---\n", retrievedChunks);

        // 1. 评估忠实度（是否基于检索内容）
        double faithfulnessScore = evaluateFaithfulness(answer, context);

        // 2. 评估相关度（是否回答了问题）
        double relevancyScore = evaluateRelevancy(query, answer);

        // 3. 检测幻觉（是否包含未在检索内容中出现的信息）
        HallucinationResult hallucinationResult = detectHallucination(answer, context);

        // 计算综合分数
        double overallScore = (faithfulnessScore * 0.4 + relevancyScore * 0.4 + (1 - hallucinationResult.getHallucinationRate()) * 0.2);

        EvaluationResult result = EvaluationResult.builder()
                .overallScore(overallScore)
                .faithfulnessScore(faithfulnessScore)
                .relevancyScore(relevancyScore)
                .hallucinationRate(hallucinationResult.getHallucinationRate())
                .hallucinationDetails(hallucinationResult.getDetails())
                .isAcceptable(overallScore >= 0.6 && hallucinationResult.getHallucinationRate() < 0.3)
                .build();

        log.info("答案质量评估完成: overallScore={}, faithfulness={}, relevancy={}, hallucinationRate={}, isAcceptable={}",
                result.getOverallScore(), result.getFaithfulnessScore(),
                result.getRelevancyScore(), result.getHallucinationRate(), result.isAcceptable());

        return result;
    }

    /**
     * 评估忠实度 — 答案是否基于检索内容
     */
    private double evaluateFaithfulness(String answer, String context) {
        String prompt = """
                请评估以下答案是否忠实于提供的参考资料。
                
                评分标准：
                - 1.0分：答案完全基于参考资料，没有添加额外信息
                - 0.8分：答案主要基于参考资料，有少量合理推断
                - 0.6分：答案部分基于参考资料，有一些未提及的信息
                - 0.4分：答案与参考资料关联较弱，多为补充信息
                - 0.2分：答案与参考资料基本无关
                - 0.0分：答案完全脱离参考资料
                
                参考资料：
                %s
                
                答案：
                %s
                
                请只返回一个0-1之间的数字分数，不要解释：
                """.formatted(truncate(context, 3000), truncate(answer, 1000));

        return callLLMForScore(prompt);
    }

    /**
     * 评估相关度 — 答案是否回答了问题
     */
    private double evaluateRelevancy(String query, String answer) {
        String prompt = """
                请评估以下答案是否回答了用户的问题。
                
                评分标准：
                - 1.0分：完全回答了问题，信息准确且完整
                - 0.8分：基本回答了问题，但缺少一些细节
                - 0.6分：部分回答了问题，但有遗漏
                - 0.4分：回答与问题相关，但没有直接回答
                - 0.2分：回答与问题关联较弱
                - 0.0分：完全没有回答问题
                
                用户问题：%s
                
                答案：
                %s
                
                请只返回一个0-1之间的数字分数，不要解释：
                """.formatted(truncate(query, 500), truncate(answer, 1000));

        return callLLMForScore(prompt);
    }

    /**
     * 检测幻觉 — 答案中包含未在检索内容中出现的信息
     */
    private HallucinationResult detectHallucination(String answer, String context) {
        String prompt = """
                请检测答案中是否存在"幻觉"（即未在参考资料中出现的信息）。
                
                分析要求：
                1. 逐句检查答案中的每个事实性陈述
                2. 判断每个陈述是否有参考资料支撑
                3. 计算幻觉比例（幻觉语句数/总语句数）
                
                参考资料：
                %s
                
                答案：
                %s
                
                请按以下格式返回：
                幻觉比例: 0.0-1.0之间的数字
                幻觉内容: 列出具体的幻觉语句（如有）
                
                示例输出：
                幻觉比例: 0.2
                幻觉内容: 
                - "该功能于2020年发布"（参考资料未提及发布时间）
                """.formatted(truncate(context, 3000), truncate(answer, 1000));

        try {
            if (chatModel == null) {
                log.warn("ChatModel未配置，跳过幻觉检测");
                return HallucinationResult.builder()
                        .hallucinationRate(0.0)
                        .details("ChatModel未配置，跳过检测")
                        .build();
            }

            String result = chatModel.call(new Prompt(new UserMessage(prompt)))
                    .getResult().getOutput().getText();

            // 解析幻觉比例
            double hallucinationRate = extractHallucinationRate(result);

            return HallucinationResult.builder()
                    .hallucinationRate(hallucinationRate)
                    .details(result)
                    .build();
        } catch (Exception e) {
            log.warn("幻觉检测失败: {}", e.getMessage());
            return HallucinationResult.builder()
                    .hallucinationRate(0.0)
                    .details("检测失败: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 调用LLM获取分数
     */
    private double callLLMForScore(String prompt) {
        if (chatModel == null) {
            log.warn("ChatModel未配置，返回默认分数");
            return 0.5;
        }
        try {
            String result = chatModel.call(new Prompt(new UserMessage(prompt)))
                    .getResult().getOutput().getText();
            return extractScore(result);
        } catch (Exception e) {
            log.warn("LLM评分调用失败: {}", e.getMessage());
            return 0.5; // 默认中等分数
        }
    }

    /**
     * 从LLM响应中提取分数
     */
    private double extractScore(String response) {
        Matcher matcher = SCORE_PATTERN.matcher(response);
        if (matcher.find()) {
            double score = Double.parseDouble(matcher.group(1));
            return Math.max(0.0, Math.min(1.0, score)); // 确保在0-1范围内
        }
        return 0.5; // 默认中等分数
    }

    /**
     * 从LLM响应中提取幻觉比例
     */
    private double extractHallucinationRate(String response) {
        // 尝试匹配"幻觉比例:"后面的数字
        Pattern pattern = Pattern.compile("幻觉比例[：:]\\s*(\\d+(?:\\.\\d+)?)");
        Matcher matcher = pattern.matcher(response);
        if (matcher.find()) {
            double rate = Double.parseDouble(matcher.group(1));
            return Math.max(0.0, Math.min(1.0, rate));
        }

        // 尝试直接提取数字
        return extractScore(response);
    }

    /**
     * 截断文本
     */
    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    /**
     * 评估结果
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class EvaluationResult {
        /** 综合分数（0-1） */
        private double overallScore;
        /** 忠实度分数（0-1） */
        private double faithfulnessScore;
        /** 相关度分数（0-1） */
        private double relevancyScore;
        /** 幻觉比例（0-1，越低越好） */
        private double hallucinationRate;
        /** 幻觉详情 */
        private String hallucinationDetails;
        /** 是否可接受 */
        private boolean isAcceptable;
    }

    /**
     * 幻觉检测结果
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    private static class HallucinationResult {
        /** 幻觉比例（0-1） */
        private double hallucinationRate;
        /** 详情 */
        private String details;
    }

}
