package cn.chyuan.ai.domain.browser.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 页面状态快照值对象（AQ2：playwright accessibility snapshot 思想）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PageSnapshotVO {

    /** 页面 URL */
    private String url;

    /** 页面标题 */
    private String title;

    /** 抓取时刻毫秒 */
    private long capturedAtMs;

    /** 可交互元素清单（快照内 id 唯一） */
    private List<ElementVO> elements;
}
