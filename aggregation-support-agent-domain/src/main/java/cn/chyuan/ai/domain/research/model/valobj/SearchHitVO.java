package cn.chyuan.ai.domain.research.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 搜索结果值对象（AR2/AR3：命中项 + 可信度评分）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SearchHitVO {

    /** 标题 */
    private String title;

    /** URL */
    private String url;

    /** 内容摘要 */
    private String snippet;

    /** 可信度评分（AR3 填充，0-1） */
    private double score;

    /** 内容指纹（去重） */
    private String fingerprint;

    /** 权威分级（official/media/ugc/unknown） */
    private String authority;
}
