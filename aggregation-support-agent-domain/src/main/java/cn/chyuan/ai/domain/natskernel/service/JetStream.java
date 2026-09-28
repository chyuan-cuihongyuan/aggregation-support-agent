package cn.chyuan.ai.domain.natskernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 简化 JetStream（工单 0951 EB8，nats-server 思想）。
 * stream·consumer·ack 简化面：按主题落流/消费者游标拉取/确认移除未确认重投。
 */
public final class JetStream {

    /** 流内消息：单调序号 + 主题 + 载荷 */
    public static final class Message {
        public final long seq;
        public final String subject;
        public final String payload;

        Message(long seq, String subject, String payload) {
            this.seq = seq;
            this.subject = subject;
            this.payload = payload;
        }
    }

    static final class Stream {
        final String name;
        final List<String> subjects;
        final List<Message> messages = new ArrayList<>();
        long seq = 0;

        Stream(String name, List<String> subjects) {
            this.name = name;
            this.subjects = subjects;
        }
    }

    static final class Consumer {
        final String name;
        final String stream;
        long delivered = 0;

        Consumer(String name, String stream) {
            this.name = name;
            this.stream = stream;
        }
    }

    private final Map<String, Stream> streams = new LinkedHashMap<>();
    private final Map<String, Consumer> consumers = new LinkedHashMap<>();

    /** 建流：重复名拒绝/主题模式非法拒绝 */
    public void addStream(String name, List<String> subjects) {
        if (streams.containsKey(name)) {
            throw new IllegalArgumentException("重复流: " + name);
        }
        for (String subject : subjects) {
            Subjects.parse(subject);
        }
        streams.put(name, new Stream(name, List.copyOf(subjects)));
    }

    /** 建消费者：未知流拒绝/重复名拒绝 */
    public void addConsumer(String stream, String name) {
        Stream s = streams.get(stream);
        if (s == null) {
            throw new IllegalArgumentException("未知流: " + stream);
        }
        if (consumers.containsKey(name)) {
            throw new IllegalArgumentException("重复消费者: " + name);
        }
        consumers.put(name, new Consumer(name, stream));
    }

    /** 发布落流：返回流内序号；无匹配流拒绝 */
    public long publish(String subject, String payload) {
        Subjects.parse(subject);
        for (Stream stream : streams.values()) {
            for (String pattern : stream.subjects) {
                if (matches(pattern, subject)) {
                    Message message = new Message(++stream.seq, subject, payload);
                    stream.messages.add(message);
                    return message.seq;
                }
            }
        }
        throw new IllegalArgumentException("无匹配流: " + subject);
    }

    /** 拉取：返回当前至多 max 条未确认消息（不 ack 不消失——至少一次重投） */
    public List<Message> fetch(String consumer, int max) {
        Consumer c = consumers.get(consumer);
        if (c == null) {
            throw new IllegalArgumentException("未知消费者: " + consumer);
        }
        Stream stream = streams.get(c.stream);
        List<Message> out = new ArrayList<>();
        for (Message message : stream.messages) {
            if (out.size() >= max) {
                break;
            }
            out.add(message);
        }
        if (!out.isEmpty()) {
            c.delivered = out.get(out.size() - 1).seq;
        }
        return out;
    }

    /** 确认：按序号移除流内消息（未确认消息保留待重投） */
    public void ack(String streamName, long seq) {
        Stream stream = streams.get(streamName);
        if (stream == null) {
            throw new IllegalArgumentException("未知流: " + streamName);
        }
        stream.messages.removeIf(m -> m.seq == seq);
    }

    /** 字面相等或通配符模式匹配 */
    static boolean matches(String pattern, String subject) {
        if (pattern.equals(subject)) {
            return true;
        }
        if (Subjects.hasWildcard(pattern)) {
            return SubTree.matches(Subjects.parse(pattern), Subjects.parse(subject));
        }
        return false;
    }

    public int messageCount(String streamName) {
        Stream stream = streams.get(streamName);
        if (stream == null) {
            throw new IllegalArgumentException("未知流: " + streamName);
        }
        return stream.messages.size();
    }
}
