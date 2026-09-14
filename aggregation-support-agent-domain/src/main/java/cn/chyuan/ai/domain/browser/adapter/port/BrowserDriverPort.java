package cn.chyuan.ai.domain.browser.adapter.port;

import cn.chyuan.ai.domain.browser.model.valobj.BrowserActionVO;
import cn.chyuan.ai.domain.browser.model.valobj.PageSnapshotVO;

/**
 * 浏览器驱动端口（AQ4：infrastructure 假实现/录制器；真实 CDP/Playwright 驱动挂雾）。
 */
public interface BrowserDriverPort {

    /** 执行单个动作 → 返回动作后的新页面快照（失败抛 RuntimeException） */
    PageSnapshotVO execute(BrowserActionVO action, PageSnapshotVO current);

    /** 初始快照（任务起点） */
    PageSnapshotVO initial(String startUrl);
}
