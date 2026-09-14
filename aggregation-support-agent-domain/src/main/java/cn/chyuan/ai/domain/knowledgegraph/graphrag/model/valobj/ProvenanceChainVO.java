package cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 答案溯源链值对象（AM7：全部断言 + 锚定率）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProvenanceChainVO {

    /** 逐断言溯源 */
    private List<AssertionProvenanceVO> assertions;

    /** 锚定率（有锚点断言数 / 总断言数，空答案为 0） */
    private double anchoredRate;
}
