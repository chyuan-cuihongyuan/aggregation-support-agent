package cn.chyuan.ai.domain.txkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分布式事务内核测试（工单 1021-1028 EK1-EK8，seata 思想）。
 * 全局事务发起/分支注册/全局锁/undo log/二阶段提交/逆序回放/超时回滚/端口组合管线。
 */
class TxKernelTest {

    @Test
    void globalTransactionBegin() {
        Transactions transactions = new Transactions();
        Transactions.Tx first = transactions.begin(30);
        assertTrue(first.xid().startsWith("gid-"), "XID 生成");
        Transactions.Tx second = transactions.begin(60);
        assertNotEquals(first.xid(), second.xid(), "XID 唯一");
        assertEquals(2, transactions.count(), "重复 begin 独立新事务");
        assertEquals(Transactions.Status.ACTIVE, first.status());
        assertEquals(30, first.deadline(), "超时可配（born=0）");
        assertEquals(60, second.deadline());
        assertEquals(Transactions.Status.ACTIVE, transactions.status(first.xid()));
        assertThrows(IllegalArgumentException.class, () -> transactions.begin(0), "零超时拒绝");
        assertThrows(IllegalArgumentException.class, () -> transactions.begin(-1), "负超时拒绝");
        assertThrows(IllegalArgumentException.class, () -> transactions.get("gid-999"), "未知 XID 拒绝");
    }

    @Test
    void branchRegister() {
        Transactions transactions = new Transactions();
        Branches branches = new Branches(transactions);
        String xid = transactions.begin(30).xid();
        Branches.Branch first = branches.register(xid, "db-account");
        assertEquals(xid, first.xid(), "分支挂 XID");
        branches.register(xid, "db-order");
        assertEquals(2, branches.count(xid));
        assertThrows(IllegalStateException.class,
                () -> branches.register(xid, "db-account"), "同资源重复注册拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> branches.register("gid-999", "db"), "XID 不存在拒绝");

        String done = transactions.begin(5).xid();
        transactions.transition(done, Transactions.Status.COMMITTED);
        assertThrows(IllegalStateException.class,
                () -> branches.register(done, "db"), "已终结事务注册拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> branches.require(xid, 999), "分支未注册拒绝");
        assertEquals(first.branchId(), branches.require(xid, first.branchId()).branchId());
        assertEquals(List.of(), branches.list(transactions.begin(5).xid()), "无分支空清单");
    }

    @Test
    void globalLockConflict() {
        GlobalLock locks = new GlobalLock();
        assertTrue(locks.acquire("gid-1", "account#1"), "行键加锁");
        assertTrue(locks.acquire("gid-1", "account#1"), "同事务重入允许");
        assertFalse(locks.acquire("gid-2", "account#1"), "冲突立即失败");
        assertTrue(locks.acquire("gid-2", "account#2"));
        assertEquals("gid-1", locks.holder("account#1"));
        assertEquals(2, locks.heldCount());
        locks.release("gid-1");
        assertNull(locks.holder("account#1"), "释放清锁");
        assertTrue(locks.acquire("gid-2", "account#1"), "释放后可获锁");
        assertThrows(IllegalArgumentException.class, () -> locks.acquire("", "k"), "空事务拒绝");
        assertThrows(IllegalArgumentException.class, () -> locks.acquire("gid-1", ""), "空行键拒绝");
    }

    @Test
    void undoLogBuild() {
        UndoLog.RowImage insert = UndoLog.build("gid-1", "account", "id",
                null, Map.of("id", 1, "balance", 100));
        assertTrue(insert.isInsert(), "before 空为插入");
        assertEquals("1", insert.pk(), "主键提取");

        UndoLog.RowImage update = UndoLog.build("gid-1", "account", "id",
                Map.of("id", 1, "balance", 100), Map.of("id", 1, "balance", 80));
        assertFalse(update.isInsert());
        assertFalse(update.isDelete());

        UndoLog.RowImage delete = UndoLog.build("gid-1", "account", "id",
                Map.of("id", 1, "balance", 80), null);
        assertTrue(delete.isDelete());

        assertNull(UndoLog.build("gid-1", "account", "id",
                Map.of("id", 1, "balance", 100), Map.of("id", 1, "balance", 100)), "同值无变更跳过");

        assertThrows(IllegalArgumentException.class, () -> UndoLog.build("gid-1", "", "id", null, Map.of("id", 1)),
                "空表名拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> UndoLog.build("gid-1", "account", "", Map.of("id", 1), null), "空主键字段拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> UndoLog.build("gid-1", "account", "id", null, Map.of("no-pk", 1)), "主键缺失拒绝");
        assertThrows(IllegalArgumentException.class, () -> UndoLog.build("gid-1", "account", "id", null, null),
                "双空镜像拒绝");
    }

    @Test
    void twoPhaseCommit() {
        Transactions transactions = new Transactions();
        Branches branches = new Branches(transactions);
        TwoPhase twoPhase = new TwoPhase(transactions, branches);

        String shortTx = transactions.begin(5).xid();
        twoPhase.prepare(shortTx);
        assertEquals(Transactions.Status.COMMITTED, transactions.status(shortTx), "短事务直接提交");

        String xid = transactions.begin(10).xid();
        long b1 = branches.register(xid, "db-account").branchId();
        long b2 = branches.register(xid, "db-order").branchId();
        twoPhase.prepare(xid);
        assertEquals(Transactions.Status.COMMITTING, transactions.status(xid), "一阶段进入提交中");
        twoPhase.ackCommit(xid, b1);
        assertEquals(Transactions.Status.COMMITTING, transactions.status(xid), "部分 ack 未完成");
        twoPhase.ackCommit(xid, b2);
        assertEquals(Transactions.Status.COMMITTED, transactions.status(xid), "全员 ack 完成");
        assertTrue(twoPhase.acked(b1));

        assertThrows(IllegalArgumentException.class,
                () -> twoPhase.ackCommit(xid, 999), "未注册分支拒绝");
        assertThrows(IllegalStateException.class,
                () -> twoPhase.prepare(xid), "已终结事务 prepare 拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> twoPhase.prepare("gid-999"), "未知 XID 拒绝");

        String active = transactions.begin(5).xid();
        branches.register(active, "db");
        assertThrows(IllegalStateException.class,
                () -> twoPhase.ackCommit(active, branches.list(active).get(0).branchId()),
                "未进入 COMMITTING 直接 ack 拒绝");
    }

    @Test
    void undoReplayReverse() {
        UndoReplay replay = new UndoReplay();
        UndoLog.RowImage insert = UndoLog.build("gid-1", "account", "id",
                null, Map.of("id", 1, "balance", 100));
        UndoLog.RowImage update = UndoLog.build("gid-1", "account", "id",
                Map.of("id", 1, "balance", 100), Map.of("id", 1, "balance", 80));
        replay.write(insert);
        replay.write(update);
        assertEquals(Map.of("id", 1, "balance", 80), replay.row("account", "1"));
        assertEquals(2, replay.pendingLogs());

        replay.replay("gid-1");
        assertNull(replay.row("account", "1"), "逆序回放：update→insert 逐层撤销");
        assertEquals(0, replay.pendingLogs());

        // 脏写：他人改写当前值后回放拒绝
        replay.write(UndoLog.build("gid-2", "order", "id", null, Map.of("id", 9, "state", "new")));
        replay.write(UndoLog.build("gid-3", "order", "id",
                Map.of("id", 9, "state", "new"), Map.of("id", 9, "state", "dirty")));
        assertThrows(IllegalStateException.class, () -> replay.replay("gid-2"), "镜像不匹配脏写拒绝");

        // 无日志事务回放为空操作
        replay.replay("gid-99");
        assertEquals(2, replay.pendingLogs(), "脏写拒绝后日志保留待查");
    }

    @Test
    void timeoutRollbackSweep() {
        Transactions transactions = new Transactions();
        List<String> rolledByCallback = new java.util.ArrayList<>();
        TimeoutRollback sweeper = new TimeoutRollback(transactions, xid -> {
            transactions.transition(xid, Transactions.Status.ROLLBACKED);
            rolledByCallback.add(xid);
        });
        String slow = transactions.begin(3).xid();
        String fast = transactions.begin(1).xid();

        sweeper.sweep();
        assertEquals(0, rolledByCallback.size(), "未到期不动");
        transactions.tick();
        sweeper.sweep();
        assertEquals(List.of(fast), rolledByCallback, "到期自动回滚");
        assertEquals(Transactions.Status.ROLLBACKED, transactions.status(fast));
        assertEquals(Transactions.Status.ACTIVE, transactions.status(slow), "未到期事务不受影响");

        // COMMITTING 超时同样回滚
        String committing = transactions.begin(1).xid();
        transactions.transition(committing, Transactions.Status.COMMITTING);
        transactions.tick();
        sweeper.sweep();
        assertEquals(Transactions.Status.ROLLBACKED, transactions.status(committing));
        assertEquals(2, rolledByCallback.size(), "slow 未到期不回滚");
    }

    @Test
    void txPortPipeline() {
        TxPort port = TxPort.inMemory();
        String xid = port.begin(50);
        assertEquals(Transactions.Status.ACTIVE, port.status(xid));

        long branchId = port.registerBranch(xid, "db-account");
        assertEquals(List.of(branchId), port.branches(xid));
        assertTrue(port.lockRow(xid, "account#1"), "行键加锁");
        String rival = port.begin(50);
        assertFalse(port.lockRow(rival, "account#1"), "冲突立即失败");
        assertTrue(port.lockRow(rival, "account#2"), "他事务他行键可锁");
        TxPort other = TxPort.inMemory();
        assertThrows(IllegalArgumentException.class, () -> other.lockRow(xid, "k"), "未注册事务加锁拒绝");

        assertTrue(port.write(xid, "account", "id", null, Map.of("id", 1, "balance", 100)));
        assertTrue(port.write(xid, "account", "id",
                Map.of("id", 1, "balance", 100), Map.of("id", 1, "balance", 80)));
        assertFalse(port.write(xid, "account", "id",
                Map.of("id", 1, "balance", 80), Map.of("id", 1, "balance", 80)), "同值跳过");
        assertEquals("xid:" + xid + "@rev=2", port.revisionShape(xid), "mvcckernel revision 形态联动");

        port.prepareCommit(xid);
        assertEquals(Transactions.Status.COMMITTING, port.status(xid));
        port.ackCommit(xid, branchId);
        assertEquals(Transactions.Status.COMMITTED, port.status(xid), "全员 ack 提交");
        assertThrows(IllegalStateException.class, () -> port.rollback(xid), "已提交不可回滚");

        String rolled = port.begin(2);
        port.registerBranch(rolled, "db-order");
        assertTrue(port.write(rolled, "order", "id", null, Map.of("id", 9, "state", "new")));
        assertEquals("xid:" + rolled + "@rev=1", port.revisionShape(rolled));
        port.rollback(rolled);
        assertEquals(Transactions.Status.ROLLBACKED, port.status(rolled));

        String expired = port.begin(2);
        port.tickTime();
        assertEquals(List.of(), port.sweepTimeout(), "未到期不动");
        port.tickTime();
        assertEquals(List.of(expired), port.sweepTimeout(), "超时自动回滚");
        assertEquals(Transactions.Status.ROLLBACKED, port.status(expired));
        assertThrows(IllegalArgumentException.class, () -> port.revisionShape("gid-999"), "未知 XID 形状拒绝");
    }
}
