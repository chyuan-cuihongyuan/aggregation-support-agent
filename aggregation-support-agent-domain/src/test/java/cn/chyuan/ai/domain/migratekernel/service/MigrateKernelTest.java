package cn.chyuan.ai.domain.migratekernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 迁移账本内核测试（工单 1151-1158 EZ1-EZ8，prisma 思想）。
 * 迁移登记/顺序应用/checksum/baseline/squash/drift/失败恢复/端口组合管线。
 */
class MigrateKernelTest {

    @Test
    void ledgerRegistry() {
        MigrationLedger ledger = new MigrationLedger();
        MigrationLedger.Entry first = ledger.record("0001_init", "create table a");
        MigrationLedger.Entry second = ledger.record("0002_add_col", "alter table a");
        assertTrue(second.timestampId() > first.timestampId(), "时间戳 id 递增");
        assertThrows(IllegalArgumentException.class, () -> ledger.record("0001_init", "dup"), "重复名称拒绝");
        assertThrows(IllegalArgumentException.class, () -> ledger.record(" ", "blank"), "空名拒绝");
        assertThrows(IllegalArgumentException.class, () -> ledger.get("ghost"), "未知迁移拒绝");
        assertEquals(2, ledger.snapshot().size(), "登记后只读快照");
        assertEquals(MigrationLedger.State.PENDING, ledger.get("0001_init").state());
    }

    @Test
    void orderedApply() {
        MigratePort port = MigratePort.inMemory();
        port.record("a1", "create a");
        port.record("a2", "alter a");
        port.record("a3", "index a");
        assertEquals(List.of("a1", "a2", "a3"), port.plan(), "计划列全部未应用");
        port.failNext("a2");
        assertEquals(List.of("a1"), port.apply(), "a2 失败即停后续");
        assertEquals(List.of("a2", "a3"), port.plan(), "失败条留在计划中（a1 已应用）");
        port.retry("a2");
        assertEquals(List.of("a2", "a3"), port.apply(), "重试从失败条继续到尽（a1 已应用不重复）");
        assertTrue(port.plan().isEmpty());
        assertEquals(List.of(), port.apply(), "全应用后再 apply 幂等空转");
    }

    @Test
    void checksumTamper() {
        Checksums checksums = new Checksums();
        String fp = Checksums.fingerprint("create table a");
        assertEquals(64, fp.length(), "sha256 十六进制长度");
        assertEquals(fp, Checksums.fingerprint("create table a"), "同内容指纹稳定");
        assertNotEquals(fp, Checksums.fingerprint("create table b"));
        checksums.bind("a1", "create table a");
        checksums.verify("a1", "create table a");
        assertThrows(IllegalStateException.class, () -> checksums.verify("a1", "create table x"), "篡改检出拒绝");
        assertThrows(IllegalStateException.class, () -> checksums.bind("a1", "changed"), "已应用重绑异内容拒绝");
        assertThrows(IllegalArgumentException.class, () -> checksums.verify("ghost", "x"), "未绑定校验拒绝");

        MigratePort port = MigratePort.inMemory();
        port.record("m1", "create t");
        port.apply();
        assertThrows(IllegalArgumentException.class, () -> new Checksums().verify("m1", "create t"), "未绑定指纹不可校验");
        assertEquals("CONSISTENT", port.verify(expectedFp(port)), "账本一致通过");
    }

    private String expectedFp(MigratePort port) {
        StringBuilder expected = new StringBuilder();
        for (String name : port.applied()) {
            expected.append(Checksums.fingerprint(contentOf(name))).append('\n');
        }
        return Checksums.fingerprint(expected.toString());
    }

    private String contentOf(String name) {
        return switch (name) {
            case "m1" -> "create t";
            default -> throw new IllegalArgumentException(name);
        };
    }

    @Test
    void baseline() {
        MigratePort port = MigratePort.inMemory();
        port.record("old1", "legacy 1");
        port.record("old2", "legacy 2");
        port.record("new1", "fresh 1");
        port.baseline("old2");
        assertEquals(List.of("new1"), port.apply(), "基线段视作既有，仅续应用新迁移");
        assertEquals(List.of("old1", "old2", "new1"), port.applied(), "基线条计入已应用");
        assertThrows(IllegalStateException.class, () -> port.baseline("new1"), "重复基线拒绝");

        MigratePort conflict = MigratePort.inMemory();
        conflict.record("x1", "one");
        conflict.record("x2", "two");
        conflict.apply();
        assertThrows(IllegalStateException.class, () -> conflict.baseline("x2"), "基线区间含已应用条矛盾拒绝");
    }

    @Test
    void squashRange() {
        MigratePort port = MigratePort.inMemory();
        port.record("s1", "one");
        port.record("s2", "two");
        port.record("s3", "three");
        port.record("s4", "four");
        port.failNext("s3");
        port.apply();
        port.retry("s3");
        port.apply();
        assertEquals("s1..s3", port.squash("s1", "s3"), "连续已应用区间合并");
        assertEquals(List.of("s1..s3", "s4"), port.applied(), "新账本以合并条起账续跑 s4");
        assertTrue(port.plan().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> port.squash("s4", "s1"), "越界区间拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.squash("s1", "ghost"), "未知名拒绝");

        MigratePort pending = MigratePort.inMemory();
        pending.record("p1", "one");
        pending.record("p2", "two");
        assertThrows(IllegalStateException.class, () -> pending.squash("p1", "p2"), "未应用区间不可合并");
    }

    @Test
    void driftDetection() {
        MigratePort port = MigratePort.inMemory();
        port.record("d1", "create d");
        port.record("d2", "alter d");
        port.apply();
        StringBuilder expected = new StringBuilder();
        for (String name : port.applied()) {
            expected.append(Checksums.fingerprint(name.equals("d1") ? "create d" : "alter d")).append('\n');
        }
        assertEquals("CONSISTENT", port.verify(Checksums.fingerprint(expected.toString())), "指纹一致通过");
        assertEquals("DRIFT", port.verify("deadbeef"), "指纹不一致漂移告警");

        MigratePort missing = MigratePort.inMemory();
        missing.record("m1", "one");
        missing.apply();
        missing.record("m2", "two");
        assertEquals("DRIFT", missing.verify(Checksums.fingerprint(Checksums.fingerprint("one") + "\n")), "账本缺条（未应用）判漂移");
    }

    @Test
    void failureRecovery() {
        MigratePort port = MigratePort.inMemory();
        port.record("r1", "one");
        port.record("r2", "two");
        port.failNext("r2");
        port.apply();
        port.failNext("r2");
        port.retry("r2");
        port.apply();
        port.retry("r2");
        assertEquals(2, port.failures("r2"), "连续失败计数留痕");
        port.apply();
        assertEquals(0, port.failures("r2"), "重试成功清计数");
        assertThrows(IllegalStateException.class, () -> port.retry("r1"), "非失败条重试拒绝");

        MigratePort skip = MigratePort.inMemory();
        skip.record("s1", "one");
        skip.record("s2", "two");
        skip.failNext("s1");
        skip.apply();
        skip.resolve("s1");
        assertEquals(List.of("s2"), skip.apply(), "resolved 条永久跳过");
        assertEquals(List.of("s2"), skip.applied());
    }

    @Test
    void portPipeline() {
        MigratePort port = MigratePort.inMemory();
        port.record("v1", "create v");
        port.record("v2", "alter v");
        assertEquals(List.of("v1", "v2"), port.apply());
        assertEquals(List.of("v1", "v2"), port.applied());
        assertEquals("CONSISTENT", port.verify(expectedFp2(port)));
        assertEquals(List.of("revision", "type", "key", "value"), port.revisionsShape(), "mvcckernel Event 形状只读联动");
        assertThrows(IllegalArgumentException.class, () -> port.record("v1", "dup"), "端口登记重复拒绝");
    }

    private String expectedFp2(MigratePort port) {
        StringBuilder expected = new StringBuilder();
        expected.append(Checksums.fingerprint("create v")).append('\n');
        expected.append(Checksums.fingerprint("alter v")).append('\n');
        return Checksums.fingerprint(expected.toString());
    }
}
