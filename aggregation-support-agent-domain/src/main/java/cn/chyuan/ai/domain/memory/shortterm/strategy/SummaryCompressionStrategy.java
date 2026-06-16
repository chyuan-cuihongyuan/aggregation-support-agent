package cn.chyuan.ai.domain.memory.shortterm.strategy;

import cn.chyuan.ai.domain.memory.adapter.port.ILlmGateway;
import cn.chyuan.ai.domain.memory.shortterm.MemoryMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 摘要压缩策略
 * 
 * 当历史长度接近上限时，用 LLM 把早期的对话历史压缩成一段摘要，
 * 替换掉原始的冗长历史。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SummaryCompressionStrategy {
    
    private final ILlmGateway llmGateway;
    
    @Value("${agent.memory.shortterm.compression-threshold:30}")
    private int compressionThreshold;
    
    @Value("${agent.memory.shortterm.keep-recent:10}")
    private int keepRecent;
    
    private static final String COMPRESSION_PROMPT = """
        请将以下对话历史压缩成一段简洁的摘要，保留关键信息（用户偏好、重要决策、任务状态、技术细节）。
        
        要求：
        1. 摘要不超过 200 字
        2. 保留所有关键决策和偏好
        3. 保留当前任务进度
        4. 忽略寒暄和无关内容
        
        对话历史：
        %s
        """;
    
    /**
     * 应用摘要压缩策略
     *
     * @param messages 原始消息列表
     * @return 压缩后的消息列表
     */
    public List<MemoryMessage> apply(List<MemoryMessage> messages) {
        if (messages.size() <= compressionThreshold) {
            return messages;
        }
        
        // 分离 system 消息和非 system 消息
        List<MemoryMessage> systemMessages = messages.stream()
            .filter(msg -> "system".equals(msg.getRole()))
            .toList();
        
        List<MemoryMessage> nonSystemMessages = messages.stream()
            .filter(msg -> !"system".equals(msg.getRole()))
            .toList();
        
        // 需要压缩的部分（前面的）
        int compressEnd = nonSystemMessages.size() - keepRecent;
        List<MemoryMessage> toCompress = nonSystemMessages.subList(0, compressEnd);
        List<MemoryMessage> toKeep = nonSystemMessages.subList(compressEnd, nonSystemMessages.size());
        
        // 调用 LLM 压缩
        String conversationText = buildConversationText(toCompress);
        String summary = llmGateway.call(COMPRESSION_PROMPT.formatted(conversationText));
        
        // 构建结果
        List<MemoryMessage> result = new ArrayList<>(systemMessages);
        result.add(MemoryMessage.builder()
            .role("system")
            .content("[历史对话摘要] " + summary)
            .timestamp(Instant.now())
            .build());
        result.addAll(toKeep);
        
        log.info("摘要压缩策略: 原始{}条, 压缩{}条为摘要, 保留{}条",
            messages.size(), toCompress.size(), toKeep.size());
        
        return result;
    }
    
    private String buildConversationText(List<MemoryMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (MemoryMessage msg : messages) {
            sb.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
        }
        return sb.toString();
    }
}
