package cn.chyuan.ai.domain.vmkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编译器（工单 0802 CQ2/0804 CQ4/0806 CQ6，cpython 编译器思想）。
 * AST→字节码线性化/常量池与名字表去重/跳转目标回填/break·continue 编译糖/异常表（try 区间与处理目标）。
 */
public final class Compiler {

    public enum Opcode {
        CONST, LOAD, STORE, ADD, SUB, MUL, DIV, MOD,
        EQ, NE, LT, LE, GT, GE, AND, OR, NOT, NEG,
        JMP, JMPF, CALL, RET, THROW, HALT
    }

    public record Op(Opcode opcode, int arg) {
        @Override
        public String toString() {
            return opcode + " " + arg;
        }
    }

    /** 异常表项：try 区间 [start,end) 抛出则转 target，压入异常值 */
    public record TryEntry(int start, int end, int target) {
    }

    /** 代码对象：主代码 __main__ 或函数体 */
    public static final class Code {
        public final String name;
        public final int paramCount;
        public final List<String> params;
        public final List<Op> ops = new ArrayList<>();
        public final List<TryEntry> tryTable = new ArrayList<>();

        Code(String name, List<String> params) {
            this.name = name;
            this.params = List.copyOf(params);
            this.paramCount = params.size();
        }
    }

    /** 编译产物：常量池/名字表/主代码/函数表 */
    public static final class Program {
        private final List<Object> constants = new ArrayList<>();
        private final Map<Object, Integer> constIndex = new LinkedHashMap<>();
        private final List<String> names = new ArrayList<>();
        private final Map<String, Integer> nameIndex = new LinkedHashMap<>();
        public final Code main = new Code("__main__", List.of());
        public final Map<String, Code> functions = new LinkedHashMap<>();

        int constOf(Object v) {
            return constIndex.computeIfAbsent(v, k -> {
                constants.add(k);
                return constants.size() - 1;
            });
        }

        int nameOf(String n) {
            return nameIndex.computeIfAbsent(n, k -> {
                names.add(k);
                return names.size() - 1;
            });
        }

        public List<Object> constants() {
            return constants;
        }

        public List<String> names() {
            return names;
        }
    }

    private final Program program = new Program();
    private Code current;
    /** 循环上下文：break/continue 回填点（编译糖） */
    private final List<List<Integer>> breakPatches = new ArrayList<>();
    private final List<List<Integer>> continuePatches = new ArrayList<>();

    public Program compile(List<Parser.Stmt> ast) {
        current = program.main;
        for (Parser.Stmt s : ast) {
            stmt(s);
        }
        emit(Opcode.HALT, 0);
        return program;
    }

    private int emit(Opcode op, int arg) {
        current.ops.add(new Op(op, arg));
        return current.ops.size() - 1;
    }

    private void patch(int site, int target) {
        Op old = current.ops.get(site);
        current.ops.set(site, new Op(old.opcode(), target));
    }

    private void stmt(Parser.Stmt s) {
        switch (s) {
            case Parser.Assign a -> {
                expr(a.value());
                emit(Opcode.STORE, program.nameOf(a.name()));
            }
            case Parser.ExprStmt e -> expr(e.expr());
            case Parser.Return r -> {
                if (r.value() != null) {
                    expr(r.value());
                } else {
                    emit(Opcode.CONST, program.constOf(null));
                }
                emit(Opcode.RET, 0);
            }
            case Parser.Break b -> {
                if (breakPatches.isEmpty()) {
                    throw new IllegalArgumentException("break 出循环外");
                }
                int site = emit(Opcode.JMP, -1);
                breakPatches.get(breakPatches.size() - 1).add(site);
            }
            case Parser.Continue c -> {
                if (continuePatches.isEmpty()) {
                    throw new IllegalArgumentException("continue 出循环外");
                }
                int site = emit(Opcode.JMP, -1);
                continuePatches.get(continuePatches.size() - 1).add(site);
            }
            case Parser.Def d -> {
                Code fn = new Code(d.name(), d.params());
                Code outer = current;
                current = program.functions.computeIfAbsent(d.name(), n -> fn);
                if (current != fn) {
                    throw new IllegalArgumentException("函数重复定义: " + d.name());
                }
                for (Parser.Stmt body : d.body()) {
                    stmt(body);
                }
                emit(Opcode.CONST, program.constOf(null));
                emit(Opcode.RET, 0);
                current = outer;
            }
            case Parser.While w -> {
                int loopStart = current.ops.size();
                expr(w.cond());
                int exitJump = emit(Opcode.JMPF, -1);
                breakPatches.add(new ArrayList<>());
                continuePatches.add(new ArrayList<>());
                for (Parser.Stmt body : w.body()) {
                    stmt(body);
                }
                emit(Opcode.JMP, loopStart);
                patch(exitJump, current.ops.size());
                continuePatches.remove(continuePatches.size() - 1).forEach(site -> patch(site, loopStart));
                breakPatches.remove(breakPatches.size() - 1).forEach(site -> patch(site, current.ops.size()));
            }
            case Parser.If i -> {
                expr(i.cond());
                int elseJump = emit(Opcode.JMPF, -1);
                for (Parser.Stmt b : i.then()) {
                    stmt(b);
                }
                if (i.els().isEmpty()) {
                    patch(elseJump, current.ops.size());
                } else {
                    int overElse = emit(Opcode.JMP, -1);
                    patch(elseJump, current.ops.size());
                    for (Parser.Stmt b : i.els()) {
                        stmt(b);
                    }
                    patch(overElse, current.ops.size());
                }
            }
            case Parser.ThrowStmt t -> {
                expr(t.value());
                emit(Opcode.THROW, 0);
            }
            case Parser.TryStmt t -> {
                int start = current.ops.size();
                for (Parser.Stmt b : t.body()) {
                    stmt(b);
                }
                int end = current.ops.size();
                int overHandler = emit(Opcode.JMP, -1);
                int target = current.ops.size();
                current.tryTable.add(new TryEntry(start, end, target));
                if (t.finallyStyle()) {
                    // finally 体执行完显式重抛栈底异常值
                    for (Parser.Stmt b : t.handler()) {
                        stmt(b);
                    }
                    emit(Opcode.THROW, 0);
                } else {
                    emit(Opcode.STORE, program.nameOf(t.catchVar()));
                    for (Parser.Stmt b : t.handler()) {
                        stmt(b);
                    }
                }
                patch(overHandler, current.ops.size());
            }
        }
    }

    private void expr(Parser.Expr e) {
        switch (e) {
            case Parser.Num n -> emit(Opcode.CONST, program.constOf(n.value()));
            case Parser.Str s -> emit(Opcode.CONST, program.constOf(s.value()));
            case Parser.Bool b -> emit(Opcode.CONST, program.constOf(b.value()));
            case Parser.Var v -> emit(Opcode.LOAD, program.nameOf(v.name()));
            case Parser.Unary u -> {
                expr(u.expr());
                emit(u.op().equals("!") ? Opcode.NOT : Opcode.NEG, 0);
            }
            case Parser.Binary b -> {
                expr(b.left());
                expr(b.right());
                emit(switch (b.op()) {
                    case "+" -> Opcode.ADD;
                    case "-" -> Opcode.SUB;
                    case "*" -> Opcode.MUL;
                    case "/" -> Opcode.DIV;
                    case "%" -> Opcode.MOD;
                    case "==" -> Opcode.EQ;
                    case "!=" -> Opcode.NE;
                    case "<" -> Opcode.LT;
                    case "<=" -> Opcode.LE;
                    case ">" -> Opcode.GT;
                    case ">=" -> Opcode.GE;
                    case "&&" -> Opcode.AND;
                    default -> Opcode.OR;
                }, 0);
            }
            case Parser.Call c -> {
                for (Parser.Expr arg : c.args()) {
                    expr(arg);
                }
                emit(Opcode.CALL, program.nameOf(c.name()));
            }
        }
    }
}
