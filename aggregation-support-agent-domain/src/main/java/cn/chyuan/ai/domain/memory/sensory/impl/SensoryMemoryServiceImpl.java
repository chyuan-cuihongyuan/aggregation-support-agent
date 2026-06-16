package cn.chyuan.ai.domain.memory.sensory.impl;

import cn.chyuan.ai.domain.memory.sensory.ISensoryMemoryService;
import cn.chyuan.ai.domain.memory.sensory.InputPreprocessor;
import cn.chyuan.ai.domain.memory.sensory.SensoryFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 感知记忆服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensoryMemoryServiceImpl implements ISensoryMemoryService {
    
    private final InputPreprocessor preprocessor;
    private final SensoryFilter filter;
    
    @Override
    public String processInput(String rawInput) {
        // Step 1: 预处理
        String processed = preprocessor.preprocess(rawInput);
        
        // Step 2: 过滤检查
        if (!filter.shouldRetain(processed)) {
            log.info("输入被过滤: {}", rawInput);
            return "";
        }
        
        return processed;
    }
    
    @Override
    public boolean shouldRetain(String input) {
        return filter.shouldRetain(input);
    }
}
