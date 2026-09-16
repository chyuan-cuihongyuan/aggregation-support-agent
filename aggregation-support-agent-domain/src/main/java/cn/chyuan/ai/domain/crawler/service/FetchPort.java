package cn.chyuan.ai.domain.crawler.service;

import java.util.HashMap;
import java.util.Map;

/**
 * 抓取执行端口（工单 0426 AY8）+ 录制回放假实现。
 * 离线驱动：预置 URL→响应映射；未录制 URL 返回 NOT_RECORDED（status=-1）失败。
 * 不接真实网络（0417-D2 合规边界）。
 */
public interface FetchPort {

    /** 抓取结果 */
    record FetchResult(String url, int status, String body, long fetchedAtMs) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public boolean notRecorded() {
            return status == -1;
        }
    }

    FetchResult fetch(String url);

    /** 录制回放假实现 */
    class ReplayFetchAdapter implements FetchPort {

        private final Map<String, FetchResult> recordings = new HashMap<>();
        private final Clock clock;

        /** 时钟端口 */
        public interface Clock {

            long nowMs();
        }

        public ReplayFetchAdapter(Clock clock) {
            this.clock = clock;
        }

        public ReplayFetchAdapter record(String url, int status, String body) {
            recordings.put(url, new FetchResult(url, status, body, 0));
            return this;
        }

        @Override
        public FetchResult fetch(String url) {
            FetchResult recorded = recordings.get(url);
            if (recorded == null) {
                return new FetchResult(url, -1, null, clock.nowMs());
            }
            return new FetchResult(url, recorded.status(), recorded.body(), clock.nowMs());
        }
    }
}
