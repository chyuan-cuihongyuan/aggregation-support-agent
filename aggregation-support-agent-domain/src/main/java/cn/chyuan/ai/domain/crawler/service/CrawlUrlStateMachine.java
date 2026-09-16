package cn.chyuan.ai.domain.crawler.service;

/**
 * 爬取 URL 状态机（工单 0425 AY7）。
 * PENDING→FETCHED（成功）/PENDING→FAILED（失败或重试耗尽）/PENDING→PENDING（重试，计数+1）；
 * 终态（FETCHED/FAILED）拒绝再转移。纯函数。
 */
public class CrawlUrlStateMachine {

    /** 状态 */
    public enum State {
        PENDING, FETCHED, FAILED
    }

    /** 事件 */
    public enum Event {
        MARK_FETCHED, MARK_RETRY, MARK_FAILED
    }

    /** 转移结果 */
    public record Transition(State from, Event event, State to, boolean accepted, String reason) {
    }

    private final int maxRetries;

    public CrawlUrlStateMachine(int maxRetries) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("重试上限不可为负");
        }
        this.maxRetries = maxRetries;
    }

    /**
     * 转移：retryCount 为当前已重试次数。
     * MARK_RETRY 在 retryCount>=maxRetries 时转 FAILED（重试耗尽）。
     */
    public Transition transition(State from, Event event, int retryCount) {
        if (from == State.FETCHED || from == State.FAILED) {
            return new Transition(from, event, from, false, "终态 " + from + " 拒绝转移");
        }
        return switch (event) {
            case MARK_FETCHED -> new Transition(from, event, State.FETCHED, true, "抓取成功");
            case MARK_FAILED -> new Transition(from, event, State.FAILED, true, "抓取失败");
            case MARK_RETRY -> retryCount >= maxRetries
                    ? new Transition(from, event, State.FAILED, true, "重试耗尽（" + retryCount + ">=" + maxRetries + "）")
                    : new Transition(from, event, State.PENDING, true, "重试（第 " + (retryCount + 1) + " 次）");
        };
    }
}
