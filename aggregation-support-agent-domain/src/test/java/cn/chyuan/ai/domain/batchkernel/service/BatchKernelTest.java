package cn.chyuan.ai.domain.batchkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 推理批调度内核测试（工单 1058-1065 EO1-EO8，vllm 思想）。
 * 请求生命周期/等待队列准入/迭代级连续批/抢占重算/批完成逐出/chunked prefill/老化防饿死/端口组合管线。
 */
class BatchKernelTest {

    @Test
    void requestLifecycle() {
        BatchPort port = BatchPort.inMemory(10, 4, 4, 5, 2);
        port.submit("a", 4);
        assertEquals("WAITING", port.state("a"));
        port.step();
        assertEquals("RUNNING", port.state("a"));
        port.complete("a");
        assertEquals("COMPLETED", port.state("a"));

        port.submit("b", 4);
        port.step();
        port.fail("b");
        assertEquals("FAILED", port.state("b"));

        assertThrows(IllegalArgumentException.class, () -> port.submit("a", 4), "重复请求 id 拒绝");
        port.submit("c", 4);
        assertThrows(IllegalStateException.class, () -> port.complete("c"), "非 RUNNING 完成拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.submit("bad", 0), "非正 token 拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.state("nope"), "未知请求拒绝");
    }

    @Test
    void waitingAdmission() {
        BatchPort port = BatchPort.inMemory(10, 4, 10, 100, 2);
        port.submit("a", 6);
        port.submit("b", 6);
        port.step();
        assertEquals(List.of("a"), port.batch(), "预算 10 仅纳 a");
        assertEquals("WAITING", port.state("b"), "b 预算不足等待");
        port.step();
        assertEquals("WAITING", port.state("b"), "b 仍不足继续等待");
        port.complete("a");
        port.step();
        assertEquals(List.of("b"), port.batch(), "预算释放后 b 补位队首准入");
        assertEquals("RUNNING", port.state("b"));
    }

    @Test
    void sameTickFifo() {
        BatchPort port = BatchPort.inMemory(20, 4, 8, 100, 2);
        port.submit("x", 5);
        port.submit("y", 5);
        port.step();
        assertEquals(List.of("x", "y"), port.batch(), "同刻提交按 FIFO 准入");
    }

    @Test
    void continuousBatch() {
        ContinuousBatch cb = new ContinuousBatch(2);
        cb.admit("a");
        cb.admit("b");
        assertThrows(IllegalStateException.class, () -> cb.admit("c"), "批大小上限拒绝超纳");
        cb.admit("a");
        assertEquals(2, cb.size(), "重复纳入幂等");
        cb.evict("a");
        assertEquals(List.of("b"), cb.ids());
        cb.evict("b");
        assertTrue(cb.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new ContinuousBatch(0));

        BatchPort port = BatchPort.inMemory(30, 2, 8, 100, 2);
        port.submit("p", 4);
        port.submit("q", 4);
        port.submit("r", 4);
        port.step();
        assertEquals(List.of("p", "q"), port.batch(), "批上限 2 钳制");
        assertEquals("WAITING", port.state("r"));

        BatchPort idle = BatchPort.inMemory(10, 2, 4, 100, 2);
        assertEquals(0, idle.step(), "空批不推进");
        assertEquals(1, idle.tick());
    }

    @Test
    void preemptionAndRecompute() {
        Requests reqs = new Requests();
        Requests.Request a = reqs.add("a", 4, 0);
        Requests.Request b = reqs.add("b", 4, 0);
        Requests.Request c = reqs.add("c", 4, 0);
        reqs.toRunning("a", 1);
        reqs.toRunning("b", 5);
        reqs.toRunning("c", 6);
        reqs.toCompleted("c");
        assertSame(b, Preemptors.victim(List.of(a, b, c)), "最晚进入的 RUNNING 者为牺牲者");
        assertNull(Preemptors.victim(List.of(c)), "完成请求不可抢占");
        assertThrows(IllegalStateException.class, () -> reqs.toPreempted("c"), "完成请求抢占跃迁拒绝");

        BatchPort port = BatchPort.inMemory(10, 4, 4, 100, 2);
        port.submit("a", 4);
        port.step();
        port.submit("b", 4);
        port.step();
        port.step();
        assertEquals("PREEMPTED", port.state("b"), "预算超限抢占最晚进入者");
        assertEquals(1, port.preemptions("b"));
        assertEquals(List.of("a"), port.batch());
        assertTrue(port.usedTokens() <= 10, "抢占后预算回到限内");

        port.complete("a");
        port.step();
        assertEquals("RUNNING", port.state("b"), "抢占者重排队首重新准入重算");
        assertEquals(0, port.preemptions("a"), "未抢占者计数为 0");
    }

    @Test
    void batchEviction() {
        BatchPort port = BatchPort.inMemory(20, 4, 8, 100, 2);
        port.submit("a", 5);
        port.submit("b", 5);
        port.step();
        port.step();
        assertEquals(2, port.batch().size());
        port.complete("a");
        assertEquals(List.of("b"), port.batch(), "部分完成其余续跑");
        assertEquals("RUNNING", port.state("b"));
        assertEquals(6, port.usedTokens(), "完成后账目回收");
        port.complete("b");
        assertTrue(port.batch().isEmpty(), "全部完成清批");
        assertEquals(0, port.usedTokens(), "预算归零待再分配");
    }

    @Test
    void chunkedPrefill() {
        ChunkedPrefill cp = new ChunkedPrefill(3);
        assertEquals(List.of(3L, 3L, 2L), cp.plan(8));
        assertTrue(cp.chunked(8));
        assertFalse(cp.chunked(3));
        assertEquals(8, cp.plan(8).stream().mapToLong(Long::longValue).sum(), "块总和恰等");
        assertTrue(cp.plan(8).stream().allMatch(chunk -> chunk <= 3), "每块钳制不超块预算");
        assertThrows(IllegalArgumentException.class, () -> new ChunkedPrefill(0));

        BatchPort port = BatchPort.inMemory(20, 4, 3, 100, 2);
        port.submit("big", 7);
        port.submit("small", 2);
        port.step();
        assertEquals(2, port.batch().size(), "分块请求与小请求混批");
        assertEquals(5, port.usedTokens(), "首步 big 首块 3 + small prefill 2");
        port.step();
        port.step();
        assertEquals(11, port.usedTokens(), "末块完成 3+3+1 + small 2+1+1");
        port.step();
        assertEquals("RUNNING", port.state("big"));
        assertEquals("RUNNING", port.state("small"));
    }

    @Test
    void agingFairness() {
        Aging aging = new Aging(2, 1);
        assertEquals(0, aging.boost(0, 1), "未达阈值不提权");
        assertEquals(1, aging.boost(0, 2), "达阈值提权");
        assertEquals(1, aging.boost(0, 100), "提权上限不无限");
        assertThrows(IllegalArgumentException.class, () -> new Aging(0, 1));

        WaitingQueue queue = new WaitingQueue(new Aging(5, 1));
        Requests reqs = new Requests();
        Requests.Request late = reqs.add("late", 3, 9);
        Requests.Request early = reqs.add("early", 3, 0);
        queue.enqueue(late);
        queue.enqueue(early);
        assertSame(early, queue.next(10, 10), "提权后老请求先于新请求");
        assertSame(late, queue.next(10, 10));

        WaitingQueue fifo = new WaitingQueue(new Aging(5, 1));
        Requests r2 = new Requests();
        Requests.Request x = r2.add("x", 3, 4);
        Requests.Request y = r2.add("y", 3, 4);
        fifo.enqueue(x);
        fifo.enqueue(y);
        assertSame(x, fifo.next(10, 5), "同老化等级按队列序轮转");
        assertSame(y, fifo.next(10, 5));

        WaitingQueue blocked = new WaitingQueue(new Aging(5, 1));
        blocked.enqueue(r2.add("z", 5, 0));
        assertNull(blocked.next(4, 0), "预算不足等待");
        assertNotNull(blocked.next(5, 0), "预算补足后可纳");
    }

    @Test
    void batchPortPipeline() {
        BatchPort port = BatchPort.inMemory(12, 3, 4, 3, 2);
        port.submit("r1", 3);
        port.submit("r2", 6);
        port.submit("r3", 3);
        int stepped = port.step();
        assertEquals(3, stepped, "三请求混批一步全推进");
        assertEquals(3, port.batch().size());
        port.step();
        assertEquals("PREEMPTED", port.state("r3"), "同刻进入最晚提交者被抢占");
        assertEquals(1, port.preemptions("r3"));
        assertEquals(List.of("r1", "r2"), port.batch());
        assertTrue(port.usedTokens() <= 12);
        port.complete("r1");
        assertEquals("COMPLETED", port.state("r1"));
        port.step();
        assertEquals("RUNNING", port.state("r3"), "预算释放后抢占者重入批");
        assertEquals(List.of("prompt", "maxTokens", "temperature", "topK", "topP"),
                port.optionsShape(), "inferkernel 请求形状只读联动");
    }
}
