package cn.chyuan.ai.domain.tmemory.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * as-of 查询可见性解释值对象（工单 0365 AS4）：每条边给出可见与否与原因。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EdgeVisibilityVO {

    /** 边 */
    private MemoryEdgeVO edge;

    /** 是否可见 */
    private boolean visible;

    /** 视图口径（VALID 事实时间 / INGESTED 事务时间） */
    private String scope;

    /** 可见/不可见原因 */
    private String reason;

    /** 不可见原因常量 */
    public static final String REASON_ACTIVE = "validFrom<=t 且未失效";
    public static final String REASON_NOT_YET = "validFrom>t 尚未生效";
    public static final String REASON_INVALIDATED = "已于 t 前失效(validTo<=t)";

    /** 便捷装配 */
    public static EdgeVisibilityVO of(MemoryEdgeVO edge, boolean visible, String scope, String reason) {
        return EdgeVisibilityVO.builder().edge(edge).visible(visible).scope(scope).reason(reason).build();
    }

    /** 批量便捷 */
    public static List<EdgeVisibilityVO> list(List<EdgeVisibilityVO> items) {
        return items;
    }
}
