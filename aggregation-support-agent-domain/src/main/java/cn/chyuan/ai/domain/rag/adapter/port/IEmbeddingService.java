package cn.chyuan.ai.domain.rag.adapter.port;

import java.util.List;

/**
 * 嵌入向量计算接口 — 将文本转换为高维向量表示，用于语义检索
 * <p>
 * 实现类可对接不同的嵌入模型（智谱 embedding-3、DashScope text-embedding-v4、DeepSeek 等）
 */
public interface IEmbeddingService {

    /**
     * 单文本嵌入 — 将一段文本转换为向量
     *
     * @param text 输入文本
     * @return 浮点数组表示的向量（维度由具体实现决定）
     */
    float[] embed(String text);

    /**
     * 批量文本嵌入 — 将多段文本一次性转换为向量，减少 API 调用次数
     *
     * @param texts 输入文本列表
     * @return 向量列表，与输入顺序一一对应
     */
    List<float[]> embedBatch(List<String> texts);

    /**
     * 获取当前嵌入模型输出的向量维度
     * <p>
     * 用于降级切换时校验备用提供商维度是否与 Milvus 集合维度一致，
     * 避免写入不兼容维度的向量导致检索失败。
     *
     * @return 向量维度，如果实现类无法确定则返回 -1
     */
    default int dimension() {
        return -1;
    }

}
