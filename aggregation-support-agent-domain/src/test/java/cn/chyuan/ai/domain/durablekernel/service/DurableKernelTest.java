package cn.chyuan.ai.domain.durablekernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 持久执行内核测试（工单 1066-1073 EP1-EP8，temporal 思想）。
 * 事件历史/确定性重放/WFT sticky/版本补丁/心跳超时重试/signal 事件化/定时器事件/端口组合管线。
 */
class DurableKernelTest {

    @Test
    void eventHistory() {
        EventHistory history = EventHistory.start("wf", "in");
        EventHistory.Event first = history.events().get(0);
        assertEquals(1, first.id());
        assertEquals(EventHistory.WORKFLOW_STARTED, first.type());
        assertEquals("in", first.payload());
        EventHistory.Event second = history.append(EventHistory.ACTIVITY_COMPLETED, "r1");
        assertEquals(2, second.id(), "事件 id 连续递增");
        history.append(EventHistory.WORKFLOW_COMPLETED, "out");
        assertTrue(history.sealed());
        assertThrows(IllegalStateException.class,
                () -> history.append(EventHistory.ACTIVITY_COMPLETED, "x"), "封板后拒绝追加");
        assertThrows(UnsupportedOperationException.class,
                () -> history.events().add(new EventHistory.Event(99, "X", "x")), "只读快照篡改拒绝");
        assertThrows(IllegalArgumentException.class, () -> EventHistory.start("", "x"));
        assertThrows(IllegalArgumentException.class, () -> history.append(" ", "x"));
    }

    @Test
    void deterministicReplay() {
        Replayer.WorkflowCode code = input -> input.equals("a") ? "b" : "DONE:" + input;
        EventHistory history = EventHistory.start("wf", "a");
        assertEquals("b", Replayer.runOnline(history, code, 10));
        assertEquals("b", Replayer.replay(history.events(), code, 10), "重放与在线一致");
        assertEquals("b", Replayer.replay(history.events(), code, 10), "重放幂等");

        Replayer.WorkflowCode flaky = input -> "zzz";
        assertThrows(Replayer.NonDeterminismException.class,
                () -> Replayer.replay(history.events(), flaky, 10), "重执行不一致拒绝");

        EventHistory open = EventHistory.start("wf2", "a");
        Replayer.WorkflowCode neverDone = input -> "b";
        assertNull(Replayer.runOnline(open, neverDone, 3), "步数上限内未终态返回执行中");
        assertNull(Replayer.replay(open.events(), neverDone, 3));

        assertThrows(IllegalArgumentException.class,
                () -> Replayer.replay(List.of(), code, 10), "空历史拒绝");
        assertThrows(Replayer.NonDeterminismException.class,
                () -> Replayer.replay(List.of(new EventHistory.Event(1, "ACTIVITY_COMPLETED", "x")),
                        code, 10), "首事件非 STARTED 拒绝");

        EventHistory mixed = EventHistory.start("wf3", "a");
        mixed.append(EventHistory.SIGNAL_RECEIVED, "s1:1");
        mixed.append(EventHistory.ACTIVITY_COMPLETED, "b");
        mixed.append(EventHistory.WORKFLOW_COMPLETED, "b");
        assertEquals("b", Replayer.replay(mixed.events(), code, 10), "外部事件按序跳过不影响重放");
    }

    @Test
    void wftSticky() {
        TaskQueues queues = new TaskQueues();
        queues.enqueue("wf1");
        assertEquals(1, queues.queued());
        TaskQueues.Task task = queues.poll("worker-1");
        assertEquals("wf1", task.workflowId());
        assertEquals(1, task.attempt());
        assertTrue(queues.isSticky("wf1", "worker-1"));
        assertTrue(queues.deliver("wf1"), "sticky 命中直投");
        assertEquals(0, queues.queued(), "直投不再入队");
        assertEquals(1, queues.invalidate("worker-1"), "worker 失效任务回队");
        assertEquals(1, queues.queued());
        TaskQueues.Task again = queues.poll("worker-2");
        assertEquals(2, again.attempt(), "回队 attempt+1 重放");
        assertTrue(queues.isSticky("wf1", "worker-2"));
        queues.complete("wf1");
        assertFalse(queues.isSticky("wf1", "worker-2"));
        assertFalse(queues.deliver("wf1"), "完成后不再粘性投递需重新入队");
        assertNull(new TaskQueues().poll("worker-x"), "空队返回 null");
        assertThrows(IllegalArgumentException.class, () -> queues.enqueue(" "));
    }

    @Test
    void versionPatches() {
        Patches patches = new Patches();
        assertFalse(patches.has("p1"), "未注册走旧路径");
        patches.register("p1");
        assertTrue(patches.has("p1"), "带标记走新路径");
        patches.register("p1");
        assertEquals(1, patches.size(), "注册幂等");
        patches.graduate("p1");
        assertFalse(patches.has("p1"), "毕业后统一走新基线路径");
        assertThrows(IllegalArgumentException.class, () -> patches.graduate("p1"), "未知标记毕业拒绝");
        assertThrows(IllegalArgumentException.class, () -> patches.register(" "));
    }

    @Test
    void heartbeatTimeoutRetry() {
        Heartbeats heartbeats = new Heartbeats();
        heartbeats.schedule("a1", 100, 2);
        heartbeats.heartbeat("a1", 50);
        heartbeats.tick(150);
        assertEquals(Heartbeats.State.RUNNING, heartbeats.state("a1"), "到期端点未超时");
        heartbeats.tick(151);
        assertEquals(Heartbeats.State.RUNNING, heartbeats.state("a1"));
        assertEquals(1, heartbeats.attempts("a1"), "超时重试计数");
        heartbeats.tick(252);
        assertEquals(2, heartbeats.attempts("a1"));
        heartbeats.tick(353);
        assertEquals(Heartbeats.State.FAILED, heartbeats.state("a1"), "重试耗尽判失败");
        assertEquals(3, heartbeats.attempts("a1"));
        assertThrows(IllegalStateException.class, () -> heartbeats.heartbeat("a1", 400), "终态拒绝心跳");

        heartbeats.schedule("a2", 100, 1);
        heartbeats.cancel("a2");
        heartbeats.tick(1000);
        assertEquals(Heartbeats.State.CANCELLED, heartbeats.state("a2"), "取消传播停止心跳");
        assertThrows(IllegalArgumentException.class, () -> heartbeats.heartbeat("nope", 1));
        assertThrows(IllegalArgumentException.class, () -> heartbeats.schedule("a3", 0, 1));
    }

    @Test
    void signalEvents() {
        Signals signals = new Signals();
        signals.open("wf");
        Signals.SignalEvent first = signals.signal("wf", "approve", "ok");
        assertEquals(1, first.ordinal());
        Signals.SignalEvent second = signals.signal("wf", "approve", "ok2");
        assertEquals(2, second.ordinal(), "重复 signal 各自独立事件");
        assertEquals(List.of("approve:ok", "approve:ok2"), signals.query("wf"));
        assertEquals(2, signals.count("wf"));
        signals.close("wf");
        assertThrows(IllegalStateException.class, () -> signals.signal("wf", "x", "y"), "终态 signal 拒绝");
        assertThrows(IllegalStateException.class,
                () -> new Signals().signal("never", "x", "y"), "未开启 workflow 的 signal 拒绝");
        assertThrows(IllegalArgumentException.class, () -> signals.signal("wf2", " ", "y"));
    }

    @Test
    void timerEvents() {
        Timers timers = new Timers();
        String workflowTimer = timers.startToClose("wf", 100);
        String activityTimer = timers.heartbeatTimeout("act", 50);
        assertTrue(timers.advance(10).isEmpty(), "未到期不触发");
        List<Timers.TimerFire> first = timers.advance(60);
        assertEquals(1, first.size(), "仅 heartbeat 到期");
        assertEquals("HEARTBEAT_TIMEOUT", first.get(0).kind());
        assertEquals("act", first.get(0).ref());
        assertEquals(50, first.get(0).firedAt());
        List<Timers.TimerFire> second = timers.advance(200);
        assertEquals(1, second.size());
        assertEquals(workflowTimer, second.get(0).timerId());
        assertTrue(timers.advance(300).isEmpty(), "每定时器至多触发一次");
        String cancelled = timers.startToClose("wf2", 500);
        timers.cancel(cancelled);
        assertTrue(timers.advance(1000).isEmpty(), "取消定时器不触发");
        assertThrows(IllegalStateException.class, () -> timers.cancel(cancelled), "重复取消拒绝");
        assertThrows(IllegalArgumentException.class, () -> timers.cancel("nope"));
    }

    @Test
    void durablePortPipeline() {
        DurablePort port = DurablePort.inMemory(10);
        assertEquals("RUNNING", port.start("wf", "a", input -> "b"));
        port.signal("wf", "kick", "v1");
        assertEquals(List.of("state=RUNNING", "events=12", "signals=1"), port.query("wf"), "query 只读摘要");
        assertEquals("RUNNING", port.replay("wf", input -> "b"), "执行中重放一致");
        assertTrue(port.history("wf").get(0).startsWith("1:WORKFLOW_STARTED:"));

        assertEquals("COMPLETED:IN",
                port.start("wf2", "in", input -> "DONE:" + input.toUpperCase()));
        assertEquals("COMPLETED:IN", port.replay("wf2", input -> "DONE:" + input.toUpperCase()));
        assertThrows(Replayer.NonDeterminismException.class,
                () -> port.replay("wf2", input -> "DONE:WRONG"), "非确定重放拒绝");
        assertThrows(IllegalStateException.class, () -> port.signal("wf2", "x", "y"), "终态 signal 拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> port.start("wf", "x", input -> "x"), "重复 workflowId 拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.replay("nope", input -> "x"));
        assertEquals(List.of("runId", "seq", "pendingNodeId", "completedNodeIds", "createdAt"),
                port.checkpointShape(), "workflow 域 Checkpoint 形状只读联动");
    }
}
