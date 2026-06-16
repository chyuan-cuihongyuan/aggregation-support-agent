package cn.chyuan.ai.domain.memory.adapter.port;

/**
 * Embedding 端口接口
 * 
 * 提供文本向量化能力
 */
public interface IEmbeddingPort {
    
    /**
     * 将文本转换为向量
     * 
     * @param text 输入文本
     * @return 向量表示
     */
    float[] embed(String text);
}
