package cn.chyuan.ai.domain.natskernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 消息寻址内核测试（工单 0944-0951 EB1-EB8，nats-server 思想）。
 * subject 词法/订阅树/通配符匹配/队列组轮询/请求回复/no_responders/保活踢除/端口与简化 JetStream。
 */
class NatsKernelTest {

    @Test
    void subjectLexer() {
        assertArrayEquals(new String[]{"foo", "bar", "baz"}, Subjects.parse("foo.bar.baz"));
        assertTrue(Subjects.isLiteral("foo.bar"));
        assertTrue(Subjects.hasWildcard("foo.*"));
        assertTrue(Subjects.hasWildcard("foo.>"));
        assertThrows(IllegalArgumentException.class, () -> Subjects.parse(null), "空拒绝");
        assertThrows(IllegalArgumentException.class, () -> Subjects.parse(""), "空拒绝");
        assertThrows(IllegalArgumentException.class, () -> Subjects.parse("foo..bar"), "空 token 拒绝");
        assertThrows(IllegalArgumentException.class, () -> Subjects.parse(".foo"), "空 token 拒绝");
        assertThrows(IllegalArgumentException.class, () -> Subjects.parse("foo."), "空 token 拒绝");
        assertThrows(IllegalArgumentException.class, () -> Subjects.parse("foo bar.baz"), "非法字符拒绝");
        assertThrows(IllegalArgumentException.class, () -> Subjects.parse("foo.>.bar"), "> 居中拒绝");
        assertThrows(IllegalArgumentException.class, () -> Subjects.parse("fo>.o"), "> 非独立拒绝");
    }

    @Test
    void subTreeRegisterAndCount() {
        SubTree tree = new SubTree();
        tree.subscribe("c1", "foo.bar", null);
        assertEquals(2, tree.nodeCount(), "节点计数 foo+bar");
        tree.subscribe("c2", "foo.bar", null);
        assertEquals(2, tree.nodeCount(), "同模式复用节点");
        tree.subscribe("c1", "foo.bar", null);
        assertEquals(2, tree.responders("foo.bar"), "重复订阅幂等不增响应者");
        assertTrue(tree.unsubscribe("c1", "foo.bar"));
        assertEquals(1, tree.responders("foo.bar"));
        assertFalse(tree.unsubscribe("c1", "foo.bar"), "重复移除返回 false");
        tree.unsubscribeAll("c2");
        assertEquals(0, tree.responders("foo.bar"));
        assertThrows(IllegalArgumentException.class, () -> tree.subscribe("c1", "foo..", null), "非法模式拒绝");
    }

    @Test
    void wildcardMatchingAndLiteralFirst() {
        assertEquals(true, SubTree.matches(Subjects.parse("foo.*"), Subjects.parse("foo.bar")), "* 单层命中");
        assertEquals(false, SubTree.matches(Subjects.parse("foo.*"), Subjects.parse("foo.bar.baz")), "* 不跨层");
        assertEquals(false, SubTree.matches(Subjects.parse("foo.*"), Subjects.parse("foo")), "* 不匹配缺层");
        assertEquals(true, SubTree.matches(Subjects.parse("foo.>"), Subjects.parse("foo.bar")), "> 多层尾贪心");
        assertEquals(true, SubTree.matches(Subjects.parse("foo.>"), Subjects.parse("foo.bar.baz")));
        assertEquals(false, SubTree.matches(Subjects.parse("foo.>"), Subjects.parse("foo")), "> 至少一层");
        assertEquals(true, SubTree.matches(Subjects.parse(">"), Subjects.parse("a.b.c")), "全通配");

        SubTree tree = new SubTree();
        tree.subscribe("wild", "foo.*", null);
        tree.subscribe("lit", "foo.bar", null);
        List<SubTree.Sub> targets = tree.matchTargets("foo.bar");
        assertEquals("lit", targets.get(0).conn, "字面订阅优先投递");
        assertEquals("wild", targets.get(1).conn);
        assertEquals(1, tree.matchTargets("foo.baz").size(), "仅通配命中");
    }

    @Test
    void queueGroupRoundRobinAndCollapse() {
        SubTree tree = new SubTree();
        tree.subscribe("solo", "job.*", null);
        tree.subscribe("w1", "job.run", "g1");
        tree.subscribe("w2", "job.run", "g1");
        tree.subscribe("w3", "job.run", "g2");
        QueueGroups groups = new QueueGroups();
        List<String> first = groups.collapse(tree.matchTargets("job.run"));
        List<String> second = groups.collapse(tree.matchTargets("job.run"));
        assertEquals(List.of("solo", "w1", "w3"), first, "无组全投递 + g1 选 w1 + g2 选 w3");
        assertEquals(List.of("solo", "w2", "w3"), second, "g1 轮询至 w2，g2 独立仍 w3");
        assertThrows(IllegalArgumentException.class, () -> groups.next("empty@g", List.of()), "空成员拒绝");
    }

    @Test
    void requestReplyInboxRoundTrip() {
        SubTree tree = new SubTree();
        tree.subscribe("svc", "help.*", null);
        RequestReply rr = new RequestReply(tree::responders, 2);
        String inbox = rr.open("help.calc");
        rr.respond(inbox, "42");
        assertEquals("42", rr.fetch(inbox));
        assertThrows(IllegalStateException.class, () -> rr.fetch(inbox), "重复收取拒绝");
        assertThrows(IllegalStateException.class, () -> rr.respond("_INBOX.999", "x"), "未等待 inbox 拒绝");
        String pending = rr.open("help.calc");
        assertThrows(IllegalStateException.class, () -> rr.fetch(pending), "未回填抛等待中");
        rr.tick();
        assertEquals(1, rr.waiting(), "未到期待保留");
        rr.tick();
        assertEquals(0, rr.waiting(), "超时移除");
        assertThrows(IllegalStateException.class, () -> rr.fetch(pending), "超时后收取拒绝");
    }

    @Test
    void noRespondersFailFast() {
        SubTree tree = new SubTree();
        RequestReply rr = new RequestReply(tree::responders, 100);
        assertThrows(IllegalStateException.class, () -> rr.open("nobody.home"), "无订阅者快速失败不等待");
        tree.subscribe("svc", "nobody.home", null);
        assertEquals("_INBOX.1", rr.open("nobody.home"), "有响应者进入等待");
        SubTree partial = new SubTree();
        partial.subscribe("w1", "help.*", null);
        assertEquals(1, partial.responders("help.calc"), "通配订阅计入响应者");
    }

    @Test
    void keepaliveKickAndClear() {
        PingPong keepalive = new PingPong(3);
        keepalive.ping("c1");
        keepalive.ping("c1");
        assertFalse(keepalive.shouldKick("c1"), "未达上限不踢");
        keepalive.pong("c1");
        assertEquals(0, keepalive.outstanding("c1"), "pong 清零");
        keepalive.ping("c1");
        keepalive.ping("c1");
        keepalive.ping("c1");
        assertTrue(keepalive.shouldKick("c1"), "连续未 pong 达上限踢除");
        assertThrows(IllegalStateException.class, () -> keepalive.pong("ghost"), "未知连接 pong 拒绝");

        NatsPort port = NatsPort.inMemory();
        port.subscribe("c9", "kick.me", null);
        for (int i = 0; i < 3; i++) {
            ((InMemoryNats) port).keepalive().ping("c9");
        }
        List<String> kicked = port.tick();
        assertEquals(List.of("c9"), kicked);
        assertTrue(port.publish("kick.me", "x").isEmpty(), "踢除后订阅被清空");
    }

    @Test
    void portCompositeAndJetStream() {
        NatsPort port = NatsPort.inMemory();
        port.subscribe("a", "orders.created", null);
        port.subscribe("b", "orders.*", null);
        port.subscribe("w1", "orders.created", "pool");
        port.subscribe("w2", "orders.created", "pool");
        assertEquals(List.of("a", "b", "w1"), port.publish("orders.created", "o1"), "字面优先+组折叠");
        assertEquals(List.of("a", "b", "w2"), port.publish("orders.created", "o2"), "组内轮询推进");

        String inbox = port.request("orders.created", "q");
        port.respond(inbox, "ans");
        assertEquals("ans", port.fetch(inbox));
        assertThrows(IllegalStateException.class, () -> port.request("void.subject", "q"), "no_responders 快速失败");

        port.addStream("orders", List.of("orders.>"));
        assertEquals(1L, port.jetPublish("orders.created", "o1"));
        assertEquals(2L, port.jetPublish("orders.updated", "o2"));
        assertThrows(IllegalArgumentException.class, () -> port.jetPublish("other.topic", "x"), "无匹配流拒绝");
        port.addConsumer("orders", "auditor");
        assertEquals(2, port.consume("auditor", 10).size());
        assertEquals(2, port.consume("auditor", 10).size(), "未 ack 至少一次重投");
        port.ack("orders", 1L);
        List<JetStream.Message> rest = port.consume("auditor", 10);
        assertEquals(1, rest.size());
        assertEquals(2L, rest.get(0).seq, "ack 后仅剩未确认");

        assertEquals("msg://nats/orders.created", NatsPort.topicOf("orders.created"), "msgkernel 主题形状只读联动");
        assertThrows(IllegalArgumentException.class, () -> NatsPort.topicOf("bad..s"), "联动入口同样词法把关");
    }
}
