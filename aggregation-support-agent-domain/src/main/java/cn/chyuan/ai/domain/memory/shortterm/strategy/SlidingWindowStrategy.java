package cn.chyuan.ai.domain.memory.shortterm.strategy;

import cn.chyuan.ai.domain.memory.shortterm.MemoryMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 滑动窗口策略
 * 
 * 只保留最近 N 轮对话，更早的历史直接丢弃。
 * 优点：实现简单。缺点：早期重要信息可能被丢掉。
 */
@Slf4j
@Component
public class SlidingWindowStrategy {
    
    @Value("${agent.memory.shortterm.sliding-window-size:20}")
    private int windowSize;
    
    /**
     * 应用滑动窗口策略
     *
     * @param messages 原始消息列表
     * @return 截断后的消息列表
     */
    public List<MemoryMessage> apply(List<MemoryMessage> messages) {
        if (messages.size() <= windowSize) {
            return messages;
        }
        
        // 保留 system 消息 + 最近 N 条
        List<MemoryMessage> result = new ArrayList<>();
        
        // 保留开头的 system 消息
        for (MemoryMessage msg : messages) {
            if ("system".equals(msg.getRole())) {
                result.add(msg);
            }
        }
        
        // 取最近 windowSize 条非 system 消息
        List<MemoryMessage> recent = messages.stream()
            .filter(msg -> !"system".equals(msg.getRole()))
            .toList();
        
        int startIndex = Math.max(0, recent.size() - windowSize);
        result.addAll(recent.subList(startIndex, recent.size()));
        
        log.debug("滑动窗口策略: 原始{}条, 保留{}条", messages.size(), result.size());
        
        return result;
    }
}
