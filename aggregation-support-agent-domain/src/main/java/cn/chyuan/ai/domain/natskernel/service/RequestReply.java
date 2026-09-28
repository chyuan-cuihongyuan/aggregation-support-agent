package cn.chyuan.ai.domain.natskernel.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 请求-回复（工单 0948 EB5 / 0949 EB6，nats-server 思想）。
 * inbox 等待/应答回填/超时拒绝；no_responders 无订阅者快速失败。
 */
public final class RequestReply {

    /** 响应者探测：目标 subject 当前可投递订阅数 */
    public interface ResponderProbe {
        int responders(String subject);
    }

    static final class Pending {
        final String subject;
        String payload;
        int left;

        Pending(String subject, int left) {
            this.subject = subject;
            this.left = left;
        }
    }

    private final ResponderProbe probe;
    private final int timeoutTicks;
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private int seq = 0;

    public RequestReply(ResponderProbe probe, int timeoutTicks) {
        if (timeoutTicks <= 0) {
            throw new IllegalArgumentException("超时步数非法: " + timeoutTicks);
        }
        this.probe = probe;
        this.timeoutTicks = timeoutTicks;
    }

    /** 发起请求：无响应者快速失败；否则登记 inbox 进入等待 */
    public String open(String subject) {
        Subjects.parse(subject);
        if (probe.responders(subject) == 0) {
            throw new IllegalStateException("no responders: " + subject);
        }
        String inbox = "_INBOX." + (++seq);
        pending.put(inbox, new Pending(subject, timeoutTicks));
        return inbox;
    }

    /** 应答回填：未等待的 inbox 拒绝 */
    public void respond(String inbox, String payload) {
        Pending p = pending.get(inbox);
        if (p == null) {
            throw new IllegalStateException("未等待的 inbox: " + inbox);
        }
        p.payload = payload;
    }

    /** 收取：已回填返回载荷并出队；未回填抛等待中；超时已移除抛未等待 */
    public String fetch(String inbox) {
        Pending p = pending.get(inbox);
        if (p == null) {
            throw new IllegalStateException("未等待或已超时: " + inbox);
        }
        if (p.payload == null) {
            throw new IllegalStateException("应答未回填: " + inbox);
        }
        pending.remove(inbox);
        return p.payload;
    }

    /** 时钟步进：未回填请求倒计时，到期移除（此后 fetch 拒绝） */
    public void tick() {
        pending.values().removeIf(p -> p.payload == null && --p.left <= 0);
    }

    public int waiting() {
        return pending.size();
    }

    public int timeoutTicks() {
        return timeoutTicks;
    }
}
