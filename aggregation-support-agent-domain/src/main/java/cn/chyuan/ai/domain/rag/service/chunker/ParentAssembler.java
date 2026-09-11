package cn.chyuan.ai.domain.rag.service.chunker;

import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 父块装配器（工单 0166，W4）— 命中子块去重回取父块（Small-to-Big：小块检索、大块使用）
 * <p>
 * 纯函数内核（domain 零框架依赖）：
 * <ul>
 *   <li>命中子块按既有相关度顺序遍历，读取元数据 parent_id / parent_text
 *       （兼容既有驼峰 key parentId / parentText）</li>
 *   <li>同一父块的多个子块命中 → 去重为一条父块记录（保留首个=分数最高的子块分数），
 *       content 替换为父块文本（超长截断到上限），元数据打 parent_assembled 标记</li>
 *   <li>无 parent_id 或父块文本缺失的存量块 → 原样保留（存量兼容）</li>
 * </ul>
 */
public class ParentAssembler {

    /** 父块 ID 元数据键（工单命名，写入/读取首选） */
    public static final String META_PARENT_ID = "parent_id";
    /** 父块文本元数据键 */
    public static final String META_PARENT_TEXT = "parent_text";
    /** 既有 ParentChildChunker 写入的驼峰 key（回退兼容） */
    public static final String META_PARENT_ID_LEGACY = "parentId";
    public static final String META_PARENT_TEXT_LEGACY = "parentText";
    /** 装配标记（输出记录 metadata 内，标明 content 已替换为父块文本） */
    public static final String META_PARENT_ASSEMBLED = "parent_assembled";

    /** 默认父块长度上限（字符） */
    public static final int DEFAULT_MAX_PARENT_LENGTH = 1000;

    /** 父块文本长度上限 */
    private final int maxParentLength;

    public ParentAssembler() {
        this(DEFAULT_MAX_PARENT_LENGTH);
    }

    public ParentAssembler(int maxParentLength) {
        this.maxParentLength = maxParentLength > 0 ? maxParentLength : DEFAULT_MAX_PARENT_LENGTH;
    }

    /**
     * 装配：命中子块去重回取父块
     *
     * @param hits 检索命中结果（按相关度排序）
     * @return 装配后的结果列表（父块去重 + 存量兼容）
     */
    public List<VectorSearchResultVO> assemble(List<VectorSearchResultVO> hits) {
        List<VectorSearchResultVO> assembled = new ArrayList<>();
        if (hits == null || hits.isEmpty()) {
            return assembled;
        }

        Set<String> seenParentIds = new HashSet<>();
        for (VectorSearchResultVO hit : hits) {
            Map<String, Object> metadata = hit.getMetadata();
            String parentId = readString(metadata, META_PARENT_ID, META_PARENT_ID_LEGACY);
            String parentText = readString(metadata, META_PARENT_TEXT, META_PARENT_TEXT_LEGACY);

            if (parentId == null || parentId.isBlank() || parentText == null || parentText.isBlank()) {
                // 存量兼容：无 parent_id（或父块文本缺失）的子块原样保留
                assembled.add(hit);
                continue;
            }
            if (!seenParentIds.add(parentId)) {
                // 同一父块的后续子块命中：去重跳过（保留首个=最高分）
                continue;
            }

            // 回取父块：content 替换为父块文本（超长截断），分数沿用命中子块
            Map<String, Object> assembledMeta = metadata != null ? metadata : new java.util.HashMap<>();
            assembledMeta.put(META_PARENT_ASSEMBLED, Boolean.TRUE);
            assembled.add(VectorSearchResultVO.builder()
                    .content(truncate(parentText))
                    .score(hit.getScore())
                    .metadata(assembledMeta)
                    .build());
        }
        return assembled;
    }

    /** 元数据字符串读取：主 key 优先，回退兼容 key */
    private String readString(Map<String, Object> metadata, String primaryKey, String fallbackKey) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(primaryKey);
        if (value == null) {
            value = metadata.get(fallbackKey);
        }
        return value != null ? String.valueOf(value) : null;
    }

    /** 父块文本截断到配置上限 */
    private String truncate(String text) {
        return text.length() > maxParentLength ? text.substring(0, maxParentLength) : text;
    }
}
