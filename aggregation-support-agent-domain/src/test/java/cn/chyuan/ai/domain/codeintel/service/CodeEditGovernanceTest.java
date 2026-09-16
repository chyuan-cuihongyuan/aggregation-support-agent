package cn.chyuan.ai.domain.codeintel.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AZ6-AZ8 单测（工单 0432/0433/0434）：括号配平/编辑会话检查点/编辑审计端口。
 */
class CodeEditGovernanceTest {

    @Test
    void 括号配平与豁免报告() {
        BraceBalancer balancer = new BraceBalancer();
        assertTrue(balancer.balance("public class A { void f() { int[] a = new int[1]; } }").balanced());
        // 字符串与注释内括号豁免
        assertTrue(balancer.balance("String s = \"})[(\"; // )(\n/* { } */ char c = '{';").balanced());
        // 多余闭括号
        assertFalse(balancer.balance("int a = 1; }\n").balanced());
        // 缺失闭括号（开括号在行 1）
        BraceBalancer.Balance missing = balancer.balance("if (x) {\n  f();\n");
        assertFalse(missing.balanced());
        assertEquals(1, missing.defects().get(0).line());
        assertEquals("缺失闭括号", missing.defects().get(0).type());
        // 嵌套错型
        assertFalse(balancer.balance("{(})").balanced());
    }

    @Test
    void 编辑会话检查点回滚重做与容量() {
        EditSession session = new EditSession(2);
        // 空 undo
        assertNull(session.undo("f.java", "v0"));
        // 检查点=编辑前状态：cp(v1)→编辑到 v2→cp(v2)→编辑到 v3
        session.checkpoint("f.java", "v1");
        session.checkpoint("f.java", "v2");
        // undo 回到 v2，redo 前进到 v3
        assertEquals("v2", session.undo("f.java", "v3"));
        assertEquals("v3", session.redo("f.java", "v2"));
        // 容量淘汰最旧：容量 2，再 checkpoint v4 后 v1 已淘汰
        session.checkpoint("f.java", "v4");
        assertEquals(2, session.depth("f.java"));
        assertEquals("v4", session.undo("f.java", "v5"));
        // 新文件 checkpoint 清空 redo
        session.checkpoint("g.java", "g1");
        assertNull(session.redo("g.java", "g0"));
        // 非法容量
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new EditSession(0));
    }

    @Test
    void 编辑审计端口落库查询() {
        EditAuditPort.InMemoryEditAudit audit = new EditAuditPort.InMemoryEditAudit();
        audit.append(new EditAuditPort.EditRecord(0, "a.java", EditAuditPort.Strategy.SEARCH_REPLACE,
                true, List.of(), "cp-1", 5, 1_000));
        audit.append(new EditAuditPort.EditRecord(0, "b.java", EditAuditPort.Strategy.UNIFIED_DIFF,
                false, List.of("未命中"), "cp-2", 3, 2_000));
        audit.append(new EditAuditPort.EditRecord(0, "a.java", EditAuditPort.Strategy.UNIFIED_DIFF,
                true, List.of(), "cp-3", 8, 500));
        // 按文件查询
        assertEquals(2, audit.findByFile("a.java").size());
        // 全量按时间升序
        assertEquals(500, audit.listAll().get(0).createdAtMs());
        assertTrue(audit.listAll().get(2).createdAtMs() >= audit.listAll().get(0).createdAtMs());
        // id 到达序自增（1..3 各不相同）
        assertEquals(3, audit.listAll().size());
        assertTrue(audit.listAll().stream().allMatch(r -> r.id() >= 1 && r.id() <= 3));
        // 失败留痕
        assertFalse(audit.findByFile("b.java").get(0).success());
    }
}
