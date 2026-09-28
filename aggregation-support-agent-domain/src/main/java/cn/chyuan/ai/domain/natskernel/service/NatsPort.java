package cn.chyuan.ai.domain.natskernel.service;

import java.util.List;
import java.util.Map;

/**
 * 消息寻址端口（工单 0951 EB8，nats-server 思想）。
 * publish·request·consume 入口统一编排/与 msgkernel 主题形状作字符串形态只读联动（泛型文本不 import）/
 * nats-kernel.enabled 默认关（开启才改变行为）。
 */
public interface NatsPort {

    /** 订阅：连接 + 模式 + 可选队列组 */
    void subscribe(String conn, String pattern, String queueGroup);

    /** 取消订阅 */
    void unsubscribe(String conn, String pattern);

    /** 发布：返回投递到的连接（字面优先、队列组折叠轮询） */
    List<String> publish(String subject, String payload);

    /** 请求：登记 inbox 等待，返回 inbox id；无响应者快速失败 */
    String request(String subject, String payload);

    /** 应答回填 */
    void respond(String inbox, String payload);

    /** 收取应答 */
    String fetch(String inbox);

    /** 时钟步进：请求超时 + 保活检查，踢除连接并清空其订阅 */
    List<String> tick();

    /** 简化 JetStream：建流/建消费者/发布落流返回序号/拉取/确认 */
    void addStream(String name, List<String> subjects);

    void addConsumer(String stream, String consumer);

    long jetPublish(String subject, String payload);

    List<JetStream.Message> consume(String consumer, int max);

    void ack(String stream, long seq);

    /** msgkernel 主题形状只读联动：subject → msg 主题字符串（形状数据不 import msgkernel） */
    static String topicOf(String subject) {
        Subjects.parse(subject);
        return "msg://nats/" + subject;
    }

    static NatsPort inMemory() {
        return new InMemoryNats();
    }
}

final class InMemoryNats implements NatsPort {

    private final SubTree tree = new SubTree();
    private final QueueGroups groups = new QueueGroups();
    private final PingPong keepalive = new PingPong(3);
    private final JetStream jet = new JetStream();
    private final RequestReply requests = new RequestReply(tree::responders, 3);
    private final java.util.Set<String> conns = new java.util.LinkedHashSet<>();

    @Override
    public void subscribe(String conn, String pattern, String queueGroup) {
        conns.add(conn);
        tree.subscribe(conn, pattern, queueGroup);
    }

    @Override
    public void unsubscribe(String conn, String pattern) {
        tree.unsubscribe(conn, pattern);
    }

    @Override
    public List<String> publish(String subject, String payload) {
        return groups.collapse(tree.matchTargets(subject));
    }

    @Override
    public String request(String subject, String payload) {
        return requests.open(subject);
    }

    @Override
    public void respond(String inbox, String payload) {
        requests.respond(inbox, payload);
    }

    @Override
    public String fetch(String inbox) {
        return requests.fetch(inbox);
    }

    @Override
    public List<String> tick() {
        requests.tick();
        List<String> kicked = new java.util.ArrayList<>();
        for (String conn : conns) {
            if (keepalive.shouldKick(conn)) {
                keepalive.clear(conn);
                tree.unsubscribeAll(conn);
                kicked.add(conn);
            }
        }
        for (String conn : kicked) {
            conns.remove(conn);
        }
        return kicked;
    }

    @Override
    public void addStream(String name, List<String> subjects) {
        jet.addStream(name, subjects);
    }

    @Override
    public void addConsumer(String stream, String consumer) {
        jet.addConsumer(stream, consumer);
    }

    @Override
    public long jetPublish(String subject, String payload) {
        return jet.publish(subject, payload);
    }

    @Override
    public List<JetStream.Message> consume(String consumer, int max) {
        return jet.fetch(consumer, max);
    }

    @Override
    public void ack(String stream, long seq) {
        jet.ack(stream, seq);
    }

    SubTree tree() {
        return tree;
    }

    PingPong keepalive() {
        return keepalive;
    }

    RequestReply requests() {
        return requests;
    }

    JetStream jet() {
        return jet;
    }
}
