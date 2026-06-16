package cn.chyuan.ai.domain.memory.multimodal;

import cn.chyuan.ai.domain.memory.model.valobj.MemoryMatch;
import cn.chyuan.ai.domain.memory.model.valobj.MemoryOptions;
import cn.chyuan.ai.domain.memory.model.valobj.RecallOptions;

import java.util.List;

/**
 * 多模态记忆服务接口
 * 
 * 支持图片、音频、视频等多模态记忆的存储和检索
 */
public interface IMultimodalMemoryService {
    
    /**
     * 存储多模态记忆
     *
     * @param item    多模态记忆项
     * @param options 记忆选项
     */
    void storeMultimodalMemory(MultimodalMemoryItem item, MemoryOptions options);
    
    /**
     * 批量存储多模态记忆
     *
     * @param items   多模态记忆项列表
     * @param options 记忆选项
     */
    void storeMultimodalMemoryBatch(List<MultimodalMemoryItem> items, MemoryOptions options);
    
    /**
     * 检索多模态记忆（基于文本查询）
     *
     * @param query   文本查询
     * @param options 检索选项
     * @return 匹配的记忆列表
     */
    List<MemoryMatch> recallMultimodalMemory(String query, RecallOptions options);
    
    /**
     * 检索多模态记忆（基于图片查询）
     *
     * @param image   图片数据（Base64 或 URL）
     * @param options 检索选项
     * @return 匹配的记忆列表
     */
    List<MemoryMatch> recallByImage(String image, RecallOptions options);
    
    /**
     * 检索多模态记忆（基于音频查询）
     *
     * @param audio   音频数据（Base64 或 URL）
     * @param options 检索选项
     * @return 匹配的记忆列表
     */
    List<MemoryMatch> recallByAudio(String audio, RecallOptions options);
    
    /**
     * 删除多模态记忆
     *
     * @param memoryId 记忆 ID
     */
    void forgetMultimodalMemory(String memoryId);
    
    /**
     * 按类型检索多模态记忆
     *
     * @param modalityType 模态类型（IMAGE/AUDIO/VIDEO）
     * @param options      检索选项
     * @return 匹配的记忆列表
     */
    List<MemoryMatch> recallByModalityType(String modalityType, RecallOptions options);
}
