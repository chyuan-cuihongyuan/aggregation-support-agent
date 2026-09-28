package cn.chyuan.ai.domain.natskernel.service;

import java.util.HashMap;
import java.util.Map;

/**
 * 保活踢除（工单 0950 EB7，nats-server 思想）。
 * ping/pong 步进/超时未 pong 踢除（连接订阅由上层清空）。
 */
public final class PingPong {

    private final int maxOutstanding;
    private final Map<String, Integer> outstanding = new HashMap<>();

    public PingPong(int maxOutstanding) {
        if (maxOutstanding <= 0) {
            throw new IllegalArgumentException("未应答上限非法: " + maxOutstanding);
        }
        this.maxOutstanding = maxOutstanding;
    }

    /** 连接发起一次 ping：未应答数步进 */
    public void ping(String conn) {
        outstanding.merge(conn, 1, Integer::sum);
    }

    /** 连接应答一次 pong：计数清零 */
    public void pong(String conn) {
        if (!outstanding.containsKey(conn)) {
            throw new IllegalStateException("未知连接 pong: " + conn);
        }
        outstanding.put(conn, 0);
    }

    /** 检查一步：连续未 pong 达上限判定踢除 */
    public boolean shouldKick(String conn) {
        return outstanding.getOrDefault(conn, 0) >= maxOutstanding;
    }

    /** 踢除后清零（重连视为新连接） */
    public void clear(String conn) {
        outstanding.remove(conn);
    }

    public int outstanding(String conn) {
        return outstanding.getOrDefault(conn, 0);
    }

    public int maxOutstanding() {
        return maxOutstanding;
    }
}
