package cn.chyuan.ai.domain.browser.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 定位结果值对象（AQ3：唯一命中 / 歧义 / 未找到）
 */
@Getter
public final class LocateResult {

    /** 状态 */
    private final String status;

    /** 命中元素（HIT 时非空） */
    private final ElementVO element;

    /** 歧义候选（AMBIGUOUS 时非空） */
    private final java.util.List<ElementVO> candidates;

    private LocateResult(String status, ElementVO element, java.util.List<ElementVO> candidates) {
        this.status = status;
        this.element = element;
        this.candidates = candidates;
    }

    public static LocateResult hit(ElementVO element) {
        return new LocateResult("HIT", element, java.util.List.of());
    }

    public static LocateResult ambiguous(java.util.List<ElementVO> candidates) {
        return new LocateResult("AMBIGUOUS", null, java.util.List.copyOf(candidates));
    }

    public static LocateResult missing() {
        return new LocateResult("MISS", null, java.util.List.of());
    }
}
