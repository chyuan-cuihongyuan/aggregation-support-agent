package cn.chyuan.ai.domain.ownkernel.service;

import java.util.List;
import java.util.function.Function;

/**
 * 所有权检查端口（工单 0837 CT8，rust 借用检查思想）。
 * check 入口统一编排/与 scankernel 风格诊断结构只读联动（诊断映射函数泛型入参不 import）/
 * own-kernel.enabled 默认关（开启才改变行为）。
 */
public interface OwnPort {

    /** 检查一段所有权操作脚本 */
    List<OwnChecker.Diagnostic> check(List<Op> ops);

    /** scankernel 只读联动形态：诊断按映射函数转为宿主诊断形状（形状数据不 import scankernel） */
    <T> List<T> checkAs(List<Op> ops, Function<OwnChecker.Diagnostic, T> diagOf);

    /** 操作脚本 */
    record Op(String kind, String a, String b, int line) {
        public static Op define(String var, int line) {
            return new Op("define", var, null, line);
        }

        public static Op move(String src, String into, int line) {
            return new Op("move", src, into, line);
        }

        public static Op use(String var, int line) {
            return new Op("use", var, null, line);
        }

        public static Op borrowShared(String var, String loan, int line) {
            return new Op("borrowShared", var, loan, line);
        }

        public static Op borrowMut(String var, String loan, int line) {
            return new Op("borrowMut", var, loan, line);
        }

        public static Op loanEnd(String loan, int line) {
            return new Op("loanEnd", loan, null, line);
        }

        public static Op cloneInto(String src, String fresh, int line) {
            return new Op("cloneInto", src, fresh, line);
        }

        public static Op drop(String var, int line) {
            return new Op("drop", var, null, line);
        }

        public static Op reborrow(String loan, String fresh, int line) {
            return new Op("reborrow", loan, fresh, line);
        }

        public static Op returnBorrow(String loan, int line) {
            return new Op("returnBorrow", loan, null, line);
        }
    }

    static OwnPort inMemory() {
        return new InMemoryOwn();
    }
}

final class InMemoryOwn implements OwnPort {

    @Override
    public List<OwnChecker.Diagnostic> check(List<Op> ops) {
        OwnChecker checker = new OwnChecker();
        for (Op op : ops) {
            switch (op.kind()) {
                case "define" -> checker.define(op.a(), op.line());
                case "move" -> checker.moveFrom(op.a(), op.b(), op.line());
                case "use" -> checker.use(op.a(), op.line());
                case "borrowShared" -> checker.borrowShared(op.a(), op.b(), op.line());
                case "borrowMut" -> checker.borrowMut(op.a(), op.b(), op.line());
                case "loanEnd" -> checker.loanEnd(op.a(), op.line());
                case "cloneInto" -> checker.cloneInto(op.a(), op.b(), op.line());
                case "drop" -> checker.drop(op.a(), op.line());
                case "reborrow" -> checker.reborrow(op.a(), op.b(), op.line());
                case "returnBorrow" -> checker.returnBorrow(op.a(), op.line());
                default -> throw new IllegalArgumentException("未知操作: " + op.kind());
            }
        }
        return checker.diagnostics();
    }

    @Override
    public <T> List<T> checkAs(List<Op> ops, Function<OwnChecker.Diagnostic, T> diagOf) {
        return check(ops).stream().map(diagOf).toList();
    }
}
