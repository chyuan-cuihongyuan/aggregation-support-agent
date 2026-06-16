package cn.chyuan.ai.domain.memory.shortterm;

/**
 * Context Window 管理策略
 */
public enum ContextWindowStrategy {
    
    /** 滑动窗口：只保留最近 N 轮对话 */
    SLIDING_WINDOW,
    
    /** 摘要压缩：用 LLM 将早期对话压缩成摘要 */
    SUMMARY_COMPRESSION,
    
    /** 卸载：将不常用但重要的信息卸载到长期记忆 */
    OFFLOADING
}
