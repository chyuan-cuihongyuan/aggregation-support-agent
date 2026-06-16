package cn.chyuan.ai.domain.memory.adapter.port;

/**
 * LLM 调用网关接口
 * 用于记忆压缩等需要调用大模型的场景
 */
public interface ILlmGateway {
    
    /**
     * 调用 LLM
     *
     * @param prompt 提示词
     * @return LLM 响应文本
     */
    String call(String prompt);
    
    /**
     * 调用 LLM 并解析为 JSON 对象
     *
     * @param prompt 提示词
     * @param clazz 返回类型
     * @param <T> 泛型
     * @return 解析后的对象
     */
    <T> T callForJson(String prompt, Class<T> clazz);
}
