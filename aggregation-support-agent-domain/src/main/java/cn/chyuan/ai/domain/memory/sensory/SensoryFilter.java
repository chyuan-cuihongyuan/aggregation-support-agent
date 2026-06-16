package cn.chyuan.ai.domain.memory.sensory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 感知记忆过滤器
 * 
 * 过滤无意义的输入内容，如纯空白、过短内容、无意义寒暄等。
 */
@Slf4j
@Component
public class SensoryFilter {
    
    /** 最小有效输入长度 */
    private static final int MIN_LENGTH = 2;
    
    /** 无意义内容模式 */
    private static final Set<String> MEANINGLESS_PATTERNS = Set.of(
        "嗯", "哦", "啊", "好的", "好吧", "是", "对", "对对",
        "ok", "OK", "Ok", "k", "K", "yes", "no", "yeah", "nah"
    );
    
    /**
     * 判断输入是否应该保留
     *
     * @param input 输入内容
     * @return true=保留, false=过滤
     */
    public boolean shouldRetain(String input) {
        if (input == null || input.isBlank()) {
            log.debug("过滤空输入");
            return false;
        }
        
        String trimmed = input.trim();
        
        // 过滤过短内容
        if (trimmed.length() < MIN_LENGTH) {
            log.debug("过滤过短输入: length={}", trimmed.length());
            return false;
        }
        
        // 过滤无意义寒暄
        if (MEANINGLESS_PATTERNS.contains(trimmed)) {
            log.debug("过滤无意义输入: {}", trimmed);
            return false;
        }
        
        return true;
    }
}
