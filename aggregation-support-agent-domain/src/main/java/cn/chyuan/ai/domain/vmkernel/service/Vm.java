package cn.chyuan.ai.domain.vmkernel.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 栈式虚拟机（工单 0803 CQ3/0804 CQ4/0805 CQ5/0806 CQ6，cpython ceval 思想）。
 * 操作数栈与帧/名字查找 locals→globals/控制流跳转/函数调用递归与深度上限/异常表展开与跨帧传播/未知操作数拒绝。
 */
public final class Vm {

    /** 虚拟机错误：可被 try 表捕获，未捕获则抛出到宿主 */
    public static final class VmError extends RuntimeException {
        public VmError(String message) {
            super(message);
        }
    }

    private static final class Frame {
        final Compiler.Code code;
        final Map<String, Object> locals = new HashMap<>();
        final Deque<Object> stack = new ArrayDeque<>();
        int ip = 0;

        Frame(Compiler.Code code) {
            this.code = code;
        }
    }

    private final Compiler.Program program;
    private final Map<String, Object> globals;
    private final int maxStack;
    private final int maxDepth;

    public Vm(Compiler.Program program) {
        this(program, new LinkedHashMap<>(), 256, 64);
    }

    public Vm(Compiler.Program program, Map<String, Object> initialGlobals) {
        this(program, initialGlobals, 256, 64);
    }

    public Vm(Compiler.Program program, Map<String, Object> initialGlobals, int maxStack, int maxDepth) {
        this.program = program;
        this.globals = initialGlobals;
        this.maxStack = maxStack;
        this.maxDepth = maxDepth;
    }

    public Object run() {
        Deque<Frame> frames = new ArrayDeque<>();
        frames.push(new Frame(program.main));
        while (true) {
            Frame frame = frames.peek();
            if (frame.ip >= frame.code.ops.size()) {
                // 函数体自然结束：隐式返回 null
                Object value = frame.stack.isEmpty() ? null : frame.stack.pop();
                frames.pop();
                if (frames.isEmpty()) {
                    return value;
                }
                frames.peek().stack.push(value);
                continue;
            }
            Compiler.Op op = frame.code.ops.get(frame.ip);
            int atIp = frame.ip;
            frame.ip++;
            try {
                switch (op.opcode()) {
                    case CONST -> push(frame, operand(program.constants(), op.arg(), "常量索引"));
                    case LOAD -> {
                        String name = operand(program.names(), op.arg(), "名字索引");
                        if (frame.locals.containsKey(name)) {
                            push(frame, frame.locals.get(name));
                        } else if (globals.containsKey(name)) {
                            push(frame, globals.get(name));
                        } else {
                            throw new VmError("name '" + name + "' is not defined");
                        }
                    }
                    case STORE -> {
                        String name = operand(program.names(), op.arg(), "名字索引");
                        if (frame.code == program.main) {
                            globals.put(name, frame.stack.isEmpty() ? null : pop(frame));
                        } else {
                            frame.locals.put(name, frame.stack.isEmpty() ? null : pop(frame));
                        }
                    }
                    case ADD, SUB, MUL, DIV, MOD -> {
                        Object right = pop(frame);
                        Object left = pop(frame);
                        push(frame, arith(op.opcode(), left, right));
                    }
                    case EQ, NE, LT, LE, GT, GE -> {
                        Object right = pop(frame);
                        Object left = pop(frame);
                        push(frame, compare(op.opcode(), left, right));
                    }
                    case AND, OR -> {
                        Object right = pop(frame);
                        Object left = pop(frame);
                        boolean l = truthy(left);
                        boolean r = truthy(right);
                        push(frame, op.opcode() == Compiler.Opcode.AND ? l && r : l || r);
                    }
                    case NOT -> push(frame, !truthy(pop(frame)));
                    case NEG -> push(frame, negate(pop(frame)));
                    case JMP -> frame.ip = op.arg();
                    case JMPF -> {
                        if (!truthy(pop(frame))) {
                            frame.ip = op.arg();
                        }
                    }
                    case CALL -> {
                        String fname = operand(program.names(), op.arg(), "函数名索引");
                        Compiler.Code fn = program.functions.get(fname);
                        if (fn == null) {
                            throw new VmError("未定义函数 '" + fname + "'");
                        }
                        if (frames.size() >= maxDepth) {
                            throw new VmError("maximum recursion depth exceeded");
                        }
                        List<Object> args = new ArrayList<>();
                        for (int i = 0; i < fn.paramCount; i++) {
                            args.add(frame.stack.isEmpty() ? null : pop(frame));
                        }
                        Frame callee = new Frame(fn);
                        for (int i = 0; i < fn.paramCount; i++) {
                            callee.locals.put(fn.params.get(i), args.get(fn.paramCount - 1 - i));
                        }
                        frames.push(callee);
                    }
                    case RET -> {
                        Object value = frame.stack.isEmpty() ? null : pop(frame);
                        frames.pop();
                        if (frames.isEmpty()) {
                            return value;
                        }
                        frames.peek().stack.push(value);
                    }
                    case THROW -> unwind(frames, String.valueOf(pop(frame)), atIp);
                    case HALT -> {
                        return frame.stack.isEmpty() ? null : pop(frame);
                    }
                }
            } catch (VmError e) {
                if (frames.isEmpty()) {
                    throw e;
                }
                unwind(frames, e.getMessage(), atIp);
            }
        }
    }

    private <T> T operand(List<T> pool, int index, String what) {
        if (index < 0 || index >= pool.size()) {
            throw new VmError("非法" + what + ": " + index);
        }
        return pool.get(index);
    }

    private void push(Frame frame, Object value) {
        if (frame.stack.size() >= maxStack) {
            throw new VmError("操作数栈溢出");
        }
        frame.stack.push(value);
    }

    private Object pop(Frame frame) {
        if (frame.stack.isEmpty()) {
            throw new VmError("操作数栈下溢");
        }
        return frame.stack.pop();
    }

    /** 异常展开：取 start 最大（最内层）的匹配区间；未捕获弹帧向调用方传播；主帧未捕获则抛宿主 */
    private void unwind(Deque<Frame> frames, String message, int atIp) {
        while (true) {
            Frame frame = frames.peek();
            Compiler.TryEntry best = null;
            for (Compiler.TryEntry entry : frame.code.tryTable) {
                if (entry.start() <= atIp && atIp < entry.end()
                        && (best == null || entry.start() > best.start())) {
                    best = entry;
                }
            }
            if (best != null) {
                frame.stack.push(message);
                frame.ip = best.target();
                return;
            }
            frames.pop();
            if (frames.isEmpty()) {
                throw new VmError(message);
            }
            atIp = frames.peek().ip - 1;
        }
    }

    private boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Long n) {
            return n != 0;
        }
        if (v instanceof Double d) {
            return d != 0.0;
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        return true;
    }

    private Object arith(Compiler.Opcode op, Object left, Object right) {
        if (op == Compiler.Opcode.ADD && (left instanceof String || right instanceof String)) {
            return stringify(left) + stringify(right);
        }
        if (!(left instanceof Number) || !(right instanceof Number)) {
            throw new VmError("类型错误: " + typeName(left) + " 与 " + typeName(right) + " 不支持 " + op);
        }
        boolean fp = left instanceof Double || right instanceof Double;
        double a = ((Number) left).doubleValue();
        double b = ((Number) right).doubleValue();
        return switch (op) {
            case ADD -> fp ? (Object) (a + b) : (Object) (((Number) left).longValue() + ((Number) right).longValue());
            case SUB -> fp ? (Object) (a - b) : (Object) (((Number) left).longValue() - ((Number) right).longValue());
            case MUL -> fp ? (Object) (a * b) : (Object) (((Number) left).longValue() * ((Number) right).longValue());
            case DIV -> {
                if (b == 0.0) {
                    throw new VmError("division by zero");
                }
                yield fp ? (Object) (a / b) : (Object) (((Number) left).longValue() / ((Number) right).longValue());
            }
            case MOD -> {
                if (b == 0.0) {
                    throw new VmError("division by zero");
                }
                yield fp ? (Object) (a % b) : (Object) (((Number) left).longValue() % ((Number) right).longValue());
            }
            default -> throw new VmError("非算术操作码");
        };
    }

    private Object compare(Compiler.Opcode op, Object left, Object right) {
        int c;
        if (left instanceof Number && right instanceof Number) {
            c = Double.compare(((Number) left).doubleValue(), ((Number) right).doubleValue());
        } else if (left instanceof String && right instanceof String) {
            c = ((String) left).compareTo((String) right);
        } else if (left instanceof Boolean && right instanceof Boolean) {
            c = Boolean.compare((Boolean) left, (Boolean) right);
        } else {
            throw new VmError("类型错误: 不可比较 " + typeName(left) + " 与 " + typeName(right));
        }
        return switch (op) {
            case EQ -> c == 0;
            case NE -> c != 0;
            case LT -> c < 0;
            case LE -> c <= 0;
            case GT -> c > 0;
            default -> c >= 0;
        };
    }

    private Object negate(Object v) {
        if (v instanceof Long n) {
            return -n;
        }
        if (v instanceof Double d) {
            return -d;
        }
        throw new VmError("类型错误: 不能取负 " + typeName(v));
    }

    private String stringify(Object v) {
        return v instanceof String s ? s : String.valueOf(v);
    }

    private String typeName(Object v) {
        if (v == null) {
            return "None";
        }
        if (v instanceof Long || v instanceof Double) {
            return "number";
        }
        if (v instanceof String) {
            return "str";
        }
        if (v instanceof Boolean) {
            return "bool";
        }
        return v.getClass().getSimpleName();
    }
}
