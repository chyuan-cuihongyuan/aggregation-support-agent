package cn.chyuan.ai.domain.multimodal.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 跨模态检索结果值对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CrossModalSearchResultVO {

    private String itemId;
    /** TEXT | IMAGE */
    private String modalityType;
    private String content;
    private String imageUrl;
    private float score;
    private Map<String, Object> metadata;
}
