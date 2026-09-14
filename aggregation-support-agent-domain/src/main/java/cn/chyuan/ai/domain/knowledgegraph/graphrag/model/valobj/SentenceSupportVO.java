package cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单句支撑值对象（AM8）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SentenceSupportVO {

    /** 摘要句子 */
    private String text;

    /** 是否有图内支撑（成员实体/关系命中） */
    private boolean supported;
}
