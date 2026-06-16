package cn.chyuan.ai.domain.memory.shortterm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 短期记忆消息值对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryMessage {
    
    /** 消息角色: user/assistant/system/tool */
    private String role;
    
    /** 消息内容 */
    private String content;
    
    /** 消息时间戳 */
    private Instant timestamp;
    
    /** token 数量估算 */
    private Integer tokenCount;
    
    public static MemoryMessage user(String content) {
        return MemoryMessage.builder()
            .role("user")
            .content(content)
            .timestamp(Instant.now())
            .build();
    }
    
    public static MemoryMessage assistant(String content) {
        return MemoryMessage.builder()
            .role("assistant")
            .content(content)
            .timestamp(Instant.now())
            .build();
    }
    
    public static MemoryMessage system(String content) {
        return MemoryMessage.builder()
            .role("system")
            .content(content)
            .timestamp(Instant.now())
            .build();
    }
}
