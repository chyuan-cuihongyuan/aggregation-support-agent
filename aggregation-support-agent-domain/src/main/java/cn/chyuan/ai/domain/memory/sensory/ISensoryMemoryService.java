package cn.chyuan.ai.domain.memory.sensory;

/**
 * 感知记忆服务接口
 * 
 * 感知记忆是最短暂的一层，就是「当前这次调用的原始输入」。
 * 生命周期只有一次调用，处理完就消失，不会主动保留。
 * 负责原始输入的缓冲、预处理和过滤。
 */
public interface ISensoryMemoryService {
    
    /**
     * 处理原始输入（去噪、格式化、语言检测）
     *
     * @param rawInput 原始输入
     * @return 预处理后的输入
     */
    String processInput(String rawInput);
    
    /**
     * 判断输入是否应该保留（过滤无意义内容）
     *
     * @param input 输入内容
     * @return true=保留, false=过滤
     */
    boolean shouldRetain(String input);
}
