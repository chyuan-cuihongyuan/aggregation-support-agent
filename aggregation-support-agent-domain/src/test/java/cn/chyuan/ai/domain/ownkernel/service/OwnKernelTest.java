package cn.chyuan.ai.domain.ownkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 借用检查内核测试（工单 0830-0837 CT1-CT8，rust 思想）。
 * 所有权 move/共享可变借用/NLL 区间/冲突诊断/作用域栈/再借出/生命周期区间/端口编排。
 */
class OwnKernelTest {

    @Test
    void ownershipMoveAndUse() {
        OwnChecker c = new OwnChecker();
        c.define("s", 1);
        c.use("s", 2);
        assertEquals(0, c.diagnostics().size());
        c.moveFrom("s", "t", 3);
        c.use("s", 4);
        assertEquals(1, c.diagnostics().size());
        assertEquals("E0382", c.diagnostics().get(0).code());
        assertTrue(c.diagnostics().get(0).message().contains("moved"));
        c.moveFrom("s", "u", 5);
        assertEquals("E0382", c.diagnostics().get(1).code(), "二次 move 拒绝");
        c.use("ghost", 6);
        assertTrue(c.diagnostics().stream().anyMatch(d -> "E0425".equals(d.code())), "未初始化诊断");
    }

    @Test
    void cloneExempt() {
        OwnChecker c = new OwnChecker();
        c.define("s", 1);
        c.cloneInto("s", "s2", 2);
        c.use("s", 3);
        c.use("s2", 4);
        assertEquals(0, c.diagnostics().size(), "clone 后源与副本均可用");
    }

    @Test
    void sharedBorrowsCoexist() {
        OwnChecker c = new OwnChecker();
        c.define("s", 1);
        c.borrowShared("s", "a", 2);
        c.borrowShared("s", "b", 3);
        c.use("s", 4);
        assertEquals(0, c.diagnostics().size(), "多条共享借用并存");
    }

    @Test
    void mutExclusiveConflicts() {
        OwnChecker c = new OwnChecker();
        c.define("s", 1);
        c.borrowMut("s", "m1", 2);
        c.borrowMut("s", "m2", 3);
        assertEquals("E0499", c.diagnostics().get(0).code(), "双可变冲突");
        OwnChecker c2 = new OwnChecker();
        c2.define("s", 1);
        c2.borrowMut("s", "m", 2);
        c2.borrowShared("s", "a", 3);
        assertEquals("E0502", c2.diagnostics().get(0).code(), "可变与共享冲突");
        OwnChecker c3 = new OwnChecker();
        c3.define("s", 1);
        c3.borrowShared("s", "a", 2);
        c3.borrowMut("s", "m", 3);
        assertEquals("E0499", c3.diagnostics().get(0).code(), "共享存活期可变被拒");
    }

    @Test
    void nllLoanEndRestores() {
        OwnChecker c = new OwnChecker();
        c.define("s", 1);
        c.borrowMut("s", "m", 2);
        c.use("s", 3);
        assertEquals("E0502", c.diagnostics().get(0).code(), "借出存活期使用被拒");
        c.loanEnd("m", 4);
        c.use("s", 5);
        c.borrowMut("s", "m2", 6);
        assertEquals(1, c.diagnostics().size(), "NLL 终结后恢复使用与再借出");
    }

    @Test
    void scopeStackKillsLoans() {
        OwnChecker c = new OwnChecker();
        c.define("s", 1);
        c.scopePush("block");
        c.borrowMut("s", "m", 3);
        c.scopePop();
        c.use("s", 5);
        assertEquals(0, c.diagnostics().size(), "出作用域借出终结");
        c.loanEnd("m", 6);
        assertTrue(c.diagnostics().stream().anyMatch(d -> "E0000".equals(d.code())), "终结借出再 end 报未知");
    }

    @Test
    void reborrowChain() {
        OwnChecker c = new OwnChecker();
        c.define("s", 1);
        c.borrowMut("s", "m", 2);
        c.reborrow("m", "r1", 3);
        c.borrowShared("s", "a", 4);
        assertEquals("E0502", c.diagnostics().get(0).code(), "reborrow 存活期共享被拒");
        c.loanEnd("r1", 5);
        c.loanEnd("m", 6);
        c.borrowMut("s", "m2", 7);
        assertEquals(1, c.diagnostics().size(), "链式终结后恢复");
        OwnChecker c2 = new OwnChecker();
        c2.define("s", 1);
        c2.borrowShared("s", "a", 2);
        c2.reborrow("a", "r", 3);
        assertTrue(c2.diagnostics().stream().anyMatch(d -> d.message().contains("requires mutable")),
                "共享借出不可 reborrow 可变");
    }

    @Test
    void dropDoubleFree() {
        OwnChecker c = new OwnChecker();
        c.define("s", 1);
        c.drop("s", 2);
        c.drop("s", 3);
        assertEquals("E0494", c.diagnostics().get(0).code(), "double drop 拒绝");
        c.use("s", 4);
        assertTrue(c.diagnostics().stream().anyMatch(d -> d.message().contains("dropped")));
    }

    @Test
    void lifetimeRegionsAndDangling() {
        OwnChecker c = new OwnChecker();
        c.define("s", 1);
        c.borrowShared("s", "a", 2);
        c.borrowShared("s", "b", 5);
        c.loanEnd("a", 6);
        c.loanEnd("b", 8);
        List<OwnChecker.Region> regions = c.regions("s");
        assertEquals(2, regions.size());
        assertEquals(2, regions.get(0).birth());
        assertEquals(6, regions.get(0).death());
        assertTrue(regions.get(0).overlaps(regions.get(1)), "区间 2-6 与 5-8 重叠");
        c.returnBorrow("b", 9);
        assertEquals(0, c.diagnostics().size());
        OwnChecker d = new OwnChecker();
        d.define("s", 1);
        d.scopePush("f");
        d.borrowShared("s", "a", 4);
        d.scopePop();
        d.returnBorrow("a", 6);
        assertEquals("E0515", d.diagnostics().get(0).code(), "返回悬垂借用拒绝");
    }

    @Test
    void regionOverlapQuery() {
        OwnChecker c = new OwnChecker();
        c.define("x", 1);
        c.define("y", 1);
        c.borrowShared("x", "lx", 2);
        c.loanEnd("lx", 4);
        c.borrowShared("y", "ly", 3);
        c.loanEnd("ly", 5);
        assertTrue(c.conflicts("x", "y"), "区间 2-4 与 3-5 冲突");
        OwnChecker c2 = new OwnChecker();
        c2.define("x", 1);
        c2.define("y", 1);
        c2.borrowShared("x", "lx", 2);
        c2.loanEnd("lx", 3);
        c2.borrowShared("y", "ly", 4);
        c2.loanEnd("ly", 5);
        assertFalse(c2.conflicts("x", "y"), "区间 2-3 与 4-5 不相交");
    }

    @Test
    void portCheckAndGenericLinkage() {
        OwnPort port = OwnPort.inMemory();
        List<OwnChecker.Diagnostic> diags = port.check(List.of(
                OwnPort.Op.define("s", 1),
                OwnPort.Op.move("s", "t", 2),
                OwnPort.Op.use("s", 3)));
        assertEquals(1, diags.size());
        assertEquals("E0382", diags.get(0).code());
        List<String> shaped = port.checkAs(List.of(
                OwnPort.Op.define("s", 1),
                OwnPort.Op.borrowMut("s", "m", 2),
                OwnPort.Op.borrowShared("s", "a", 3)), d -> d.code() + "@" + d.line());
        assertEquals(List.of("E0502@3"), shaped, "诊断映射宿主形状只读联动");
        assertThrows(IllegalArgumentException.class,
                () -> port.check(List.of(new OwnPort.Op("bad", "x", null, 1))), "未知操作拒绝");
    }
}
