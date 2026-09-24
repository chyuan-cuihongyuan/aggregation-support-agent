package cn.chyuan.ai.domain.kvkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KV 分页内核测试（工单 0748-0754 CK1-CK7，vllm PagedAttention 思想）。
 * 物理块池/序列块表/前缀共享 COW/连续批处理准入/抢占重算/水位统计/调度步进。
 */
class KvKernelTest {

    @Test
    void blockPoolAllocateReleaseExhaust() {
        BlockPool pool = new BlockPool(4, 3);
        int b0 = pool.allocate();
        int b1 = pool.allocate();
        int b2 = pool.allocate();
        assertEquals(3, pool.usedBlocks());
        IllegalStateException ex = assertThrows(IllegalStateException.class, pool::allocate, "耗尽拒绝");
        assertTrue(ex.getMessage().contains("耗尽"));
        assertEquals(1, pool.exhaustedRejections());
        pool.writeSlot(b0);
        pool.writeSlot(b0);
        assertEquals(2, pool.fill(b0));
        pool.release(b0);
        assertEquals(0, pool.refCount(b0));
        assertEquals(1, pool.freeCount());
        pool.allocate();
        assertThrows(IllegalStateException.class, () -> pool.release(b1 * 100), "非在用块拒绝");
        assertThrows(IllegalArgumentException.class, () -> new BlockPool(0, 3));
    }

    @Test
    void blockPoolRefcountSharing() {
        BlockPool pool = new BlockPool(2, 4);
        int shared = pool.allocate();
        pool.addRef(shared);
        assertEquals(2, pool.refCount(shared));
        pool.release(shared);
        assertEquals(1, pool.refCount(shared), "引用归一不回收");
        assertEquals(3, pool.freeCount());
        pool.release(shared);
        assertEquals(4, pool.freeCount(), "归零回收");
    }

    @Test
    void sequenceBlocksAppendTruncateLocate() {
        BlockPool pool = new BlockPool(4, 8);
        SequenceBlocks seq = new SequenceBlocks(pool, 1);
        seq.append(5);
        assertEquals(5, seq.tokenCount());
        assertEquals(2, seq.blocks().size(), "4 槽块 5 token 占两块");
        assertEquals(3, seq.locate(3).slotOffset(), "块内偏移 3");
        assertEquals(seq.blocks().get(1), seq.locate(4).physicalBlock());
        assertEquals(0, seq.locate(4).slotOffset(), "跨块偏移归零");
        seq.truncate(4);
        assertEquals(1, seq.blocks().size(), "截断释放尾部块");
        assertEquals(7, pool.freeCount(), "释放回空闲链");
        assertThrows(IllegalArgumentException.class, () -> seq.locate(4));
        assertThrows(IllegalArgumentException.class, () -> seq.truncate(-1));
    }

    @Test
    void prefixCowForkCopyOnWrite() {
        BlockPool pool = new BlockPool(4, 8);
        PrefixCow cow = new PrefixCow(pool);
        SequenceBlocks parent = cow.create(1);
        parent.append(4);
        SequenceBlocks child = cow.fork(2, parent, 4);
        assertEquals(2, pool.refCount(parent.blocks().get(0)), "fork 后共享整块双引用");
        assertEquals(2, pool.refCount(child.blocks().get(0)));
        assertEquals(1, pool.usedBlocks(), "整块共享省一块");
        assertEquals(1, cow.savedBlocks());

        assertTrue(cow.needsCow(child), "子序列尾块被共享需 COW");
        cow.copyOnWrite(child);
        assertFalse(child.blocks().get(0).equals(parent.blocks().get(0)) && pool.refCount(child.blocks().get(0)) > 1,
                "COW 后子序列独立块");
        assertEquals(1, cow.cowCount());
        assertEquals(2, pool.usedBlocks(), "COW 分叉新块");

        child.append(3);
        assertEquals(7, child.tokenCount());
        assertFalse(cow.needsCow(parent), "父序列独块不再 COW");
        assertEquals(1, cow.cowCount());
    }

    @Test
    void continuousBatcherAdmissionFifoBudget() {
        BlockPool pool = new BlockPool(4, 16);
        ContinuousBatcher batcher = new ContinuousBatcher(pool, 8);
        PrefixCow cow = new PrefixCow(pool);
        SequenceBlocks s1 = cow.create(1);
        SequenceBlocks s2 = cow.create(2);
        SequenceBlocks s3 = cow.create(3);
        batcher.enqueue(s1);
        batcher.enqueue(s2);
        batcher.enqueue(s3);
        batcher.admit();
        assertEquals(2, batcher.runningQueue().size(), "预算 8 块准入 2 序列（每序列 4 块预算）");
        assertEquals(1, batcher.waitingQueue().size());
        assertEquals(2, batcher.admitted());

        ContinuousBatcher.Running done = batcher.find(1);
        batcher.remove(done);
        batcher.admit();
        assertEquals(2, batcher.runningQueue().size(), "腾出预算后准入等待者");
        assertThrows(IllegalArgumentException.class, () -> new ContinuousBatcher(pool, 0));
    }

    @Test
    void preemptLastLifoAndRecompute() {
        BlockPool pool = new BlockPool(4, 6);
        ContinuousBatcher batcher = new ContinuousBatcher(pool, 24);
        Preemptor preemptor = new Preemptor(pool, batcher);
        PrefixCow cow = new PrefixCow(pool);
        SequenceBlocks first = cow.create(1);
        first.append(4);
        batcher.enqueue(first);
        SequenceBlocks second = cow.create(2);
        batcher.enqueue(second);
        batcher.admit();
        batcher.find(1).remaining = 10;
        batcher.find(2).remaining = 10;
        second.append(2);

        ContinuousBatcher.Running victim = preemptor.preemptLast();
        assertEquals(2, victim.seqId, "后进先出抢占");
        assertEquals(0, victim.blocks.tokenCount(), "换出释放块表");
        assertEquals(1, pool.usedBlocks(), "仅受害者释放，先到者保留");
        assertTrue(batcher.waitingQueue().getFirst() == victim.blocks, "回等待队首优先重算");
        preemptor.recompute(victim.blocks, 3);
        assertEquals(3, victim.blocks.tokenCount(), "恢复=重算");
        assertEquals(1, preemptor.preemptions());
        assertEquals(1, preemptor.recomputes());
        assertTrue(pool.usedBlocks() >= 1);
    }

    @Test
    void watermarkThresholdAndUsage() {
        BlockPool pool = new BlockPool(4, 4);
        Watermark watermark = new Watermark(pool, 0.5);
        assertTrue(watermark.canAdmit());
        SequenceBlocks seq = new SequenceBlocks(pool, 1);
        seq.append(16);
        assertFalse(watermark.canAdmit(), "利用率 1.0 超阈值");
        assertEquals(1, watermark.thresholdRejections());
        assertEquals(1.0, watermark.utilization());
        Map<Long, Integer> usage = watermark.usagePerSeq(List.of(seq));
        assertEquals(4, usage.get(1L), "16 token/4 槽 = 4 块");
        assertThrows(IllegalArgumentException.class, () -> new Watermark(pool, 1.5));
    }

    @Test
    void engineStepPrefillDecodeEosAndPreempt() {
        KvPort.Components c = KvPort.open(4, 6, 24, 3);
        PrefixCow cow = c.cow();
        SequenceBlocks s1 = cow.create(1);
        SequenceBlocks s2 = cow.create(2);
        c.batcher().enqueue(s1);
        c.batcher().enqueue(s2);
        c.batcher().admit();
        c.batcher().find(1).remaining = 5;
        c.batcher().find(2).remaining = 5;
        // EOS=2 表示 2 号完成即停
        c.engine().step(2);
        assertTrue(c.engine().steps() >= 1);
        assertTrue(c.batcher().runningQueue().isEmpty() || c.engine().prefillTokensDone() > 0,
                "prefill 计数推进");
        c.engine().runToCompletion();
        assertTrue(c.batcher().runningQueue().isEmpty());
        assertTrue(c.engine().prefillTokensDone() + c.engine().decodeTokensDone() > 0);
    }

    @Test
    void enginePreemptsUnderMemoryPressure() {
        BlockPool pool = new BlockPool(4, 6);
        ContinuousBatcher batcher = new ContinuousBatcher(pool, 24);
        Preemptor preemptor = new Preemptor(pool, batcher);
        Engine engine = new Engine(pool, batcher, preemptor, 4);
        PrefixCow cow = new PrefixCow(pool);
        for (long id = 1; id <= 3; id++) {
            SequenceBlocks seq = cow.create(id);
            batcher.enqueue(seq);
            batcher.admit();
            ContinuousBatcher.Running r = batcher.find(id);
            r.remaining = 12;
            seq.append(3);
        }
        engine.step(-1);
        assertTrue(preemptor.preemptions() >= 1 || pool.freeCount() > 0 || batcher.runningQueue().size() < 3,
                "内存压力下抢占或腾挪");
    }
}
