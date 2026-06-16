package cn.chyuan.ai.domain.memory.service;

/**
 * 记忆系统 LLM 调用端口接口
 * <p>
 * 用于在 domain 层调用 LLM 服务，由 infrastructure 层实现
 */
public interface IMemoryLlmPort {
    
    /**
     * 调用 LLM
     *
     * @param prompt 提示词
     * @return LLM 响应
     */
    String call(String prompt);
}
