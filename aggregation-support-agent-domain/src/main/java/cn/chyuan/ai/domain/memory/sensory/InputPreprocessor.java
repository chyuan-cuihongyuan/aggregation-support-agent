package cn.chyuan.ai.domain.memory.sensory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 输入预处理器
 * 
 * 对原始输入进行去噪、格式化、语言检测等预处理操作。
 */
@Slf4j
@Component
public class InputPreprocessor {
    
    /**
     * 预处理输入
     * 
     * @param rawInput 原始输入
     * @return 预处理后的输入
     */
    public String preprocess(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return "";
        }
        
        String processed = rawInput;
        
        // Step 1: 去除首尾空白
        processed = processed.trim();
        
        // Step 2: 规范化空白字符（多个空格合并为一个）
        processed = processed.replaceAll("\\s+", " ");
        
        // Step 3: 去除控制字符
        processed = processed.replaceAll("[\\p{Cc}]", "");
        
        log.debug("输入预处理完成: 原始长度={}, 处理后长度={}", rawInput.length(), processed.length());
        
        return processed;
    }
}
