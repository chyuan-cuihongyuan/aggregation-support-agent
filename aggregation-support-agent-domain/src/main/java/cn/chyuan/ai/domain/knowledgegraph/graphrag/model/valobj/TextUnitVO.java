package cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文本块值对象（AM1：文档切分的 text_units，graphrag/llama_index 索引思想）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TextUnitVO {

    /** 块ID（documentId#u{序号}） */
    private String unitId;

    /** 来源文档ID */
    private String documentId;

    /** 块序号（文档内从 0 递增） */
    private int ordinal;

    /** 块内容 */
    private String content;
}
