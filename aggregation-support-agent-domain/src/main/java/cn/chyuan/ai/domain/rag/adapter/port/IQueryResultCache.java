package cn.chyuan.ai.domain.rag.adapter.port;

import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;

import java.util.List;
import java.util.function.Function;

/**
 * 查询结果缓存 Port —— 缓存是基础设施关注点，由 infrastructure 层提供实现（如 Caffeine）。
 * <p>
 * domain 层通过此接口解耦缓存技术选型，便于替换为 Redis/本地缓存等不同实现。
 *
 * @author chyuan
 * @since 2026-06-13
 */
public interface IQueryResultCache {

    /**
     * 原子地获取或计算：命中缓存则返回缓存值，未命中则用 loader 计算并写入缓存。
     * <p>
     * 实现必须保证复合操作（get-then-put）的原子性，避免高并发下重复执行 loader（竞态条件）。
     *
     * @param key    缓存 key
     * @param loader 未命中时的计算函数
     * @return 缓存或新计算的结果
     */
    List<VectorSearchResultVO> getOrCompute(String key, Function<String, List<VectorSearchResultVO>> loader);

    /**
     * 清空所有缓存（文档上传/删除时调用，避免返回过期结果）。
     */
    void invalidateAll();
}
