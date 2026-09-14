package cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单断言溯源值对象（AM7：断言句 → 证据锚点列表）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AssertionProvenanceVO {

    /** 断言句原文 */
    private String text;

    /** 证据锚点（E:实体键 / R:边键 / U:文本块ID） */
    private List<String> anchors;

    /** 是否无锚点 */
    private boolean unanchored;
}
