package cn.chyuan.ai.domain.irkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IR 模型与构建/打印（工单 0838 CU1/0844 CU7，llvm IR 思想）。
 * SSA 虚拟寄存器版本化/块与后继/文本序列化与往返解析/非法指令拒绝。
 */
public final class Ir {

    public enum Opcode { CONST, ADD, SUB, MUL, DIV, CMP, BR, JMP, RET, PRINT }

    /** 指令：dest 为产出寄存器（terminator/side-effect 为 -1），srcs 引用寄存器，const 为常量值 */
    public static final class Inst {
        public final Opcode opcode;
        public int dest = -1;
        public final List<Integer> srcs = new ArrayList<>();
        public final List<Integer> targets = new ArrayList<>();
        public long constValue;

        public Inst(Opcode opcode) {
            this.opcode = opcode;
        }

        public boolean hasSideEffect() {
            return opcode == Opcode.PRINT;
        }

        public boolean isTerminator() {
            return opcode == Opcode.BR || opcode == Opcode.JMP || opcode == Opcode.RET;
        }
    }

    public static final class Block {
        public final int id;
        public final List<Inst> insts = new ArrayList<>();
        public final List<Integer> successors = new ArrayList<>();

        Block(int id) {
            this.id = id;
        }
    }

    public final Map<Integer, Block> blocks = new LinkedHashMap<>();
    public final List<Integer> blockOrder = new ArrayList<>();
    private int nextReg = 0;
    private int entry = -1;

    public int newReg() {
        return nextReg++;
    }

    public int vregs() {
        return nextReg;
    }

    public Block block(int id) {
        return blocks.get(id);
    }

    public Block newBlock() {
        int id = blockOrder.size();
        Block b = new Block(id);
        blocks.put(id, b);
        blockOrder.add(id);
        if (entry < 0) {
            entry = id;
        }
        return b;
    }

    public int entry() {
        if (entry < 0) {
            entry = blockOrder.isEmpty() ? -1 : blockOrder.get(0);
        }
        return entry;
    }

    public int instructions() {
        int n = 0;
        for (Block b : blocks.values()) {
            n += b.insts.size();
        }
        return n;
    }

    public Inst emit(Block block, Opcode op) {
        Inst inst = new Inst(op);
        block.insts.add(inst);
        return inst;
    }

    /** 顺序拼接为线性编号（块序×块内序） */
    public List<Inst> linear() {
        List<Inst> out = new ArrayList<>();
        for (int id : blockOrder) {
            out.addAll(blocks.get(id).insts);
        }
        return out;
    }

    /** 文本序列化 */
    public String print() {
        StringBuilder sb = new StringBuilder();
        for (int id : blockOrder) {
            sb.append("b").append(id).append(":\n");
            for (Inst inst : blocks.get(id).insts) {
                sb.append("  ").append(render(inst)).append('\n');
            }
        }
        return sb.toString();
    }

    private String render(Inst inst) {
        switch (inst.opcode) {
            case CONST:
                return "%" + inst.dest + " = const " + inst.constValue;
            case ADD, SUB, MUL, DIV, CMP:
                return "%" + inst.dest + " = " + inst.opcode.name().toLowerCase() + " %" + inst.srcs.get(0)
                        + ", %" + inst.srcs.get(1);
            case BR:
                return "br %" + inst.srcs.get(0) + ", b" + inst.targets.get(0) + ", b" + inst.targets.get(1);
            case JMP:
                return "jmp b" + inst.targets.get(0);
            case RET:
                return "ret %" + inst.srcs.get(0);
            case PRINT:
                return "print %" + inst.srcs.get(0);
            default:
                throw new IllegalArgumentException("非法指令: " + inst.opcode);
        }
    }

    /** 解析（print 的逆），非法行拒绝 */
    public static Ir parse(String text) {
        Ir ir = new Ir();
        Block current = null;
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("b") && line.endsWith(":")) {
                current = ir.newBlock();
                if (!line.equals("b" + current.id + ":")) {
                    throw new IllegalArgumentException("非法块标号: " + line);
                }
                continue;
            }
            if (current == null) {
                throw new IllegalArgumentException("指令在块外: " + line);
            }
            parseInst(ir, current, line);
        }
        return ir;
    }

    private static void parseInst(Ir ir, Block block, String line) {
        String[] parts = line.replace(",", "").split("\\s+");
        try {
            if (parts[1].equals("=")) {
                String op = parts[2];
                Inst inst = ir.emit(block, Opcode.valueOf(op.toUpperCase()));
                inst.dest = reg(parts[0]);
                if (op.equals("const")) {
                    inst.constValue = Long.parseLong(parts[3]);
                } else {
                    inst.srcs.add(reg(parts[3]));
                    inst.srcs.add(reg(parts[4]));
                }
                return;
            }
            switch (parts[0]) {
                case "br" -> {
                    Inst inst = ir.emit(block, Opcode.BR);
                    inst.srcs.add(reg(parts[1]));
                    inst.targets.add(blockId(parts[2]));
                    inst.targets.add(blockId(parts[3]));
                    block.successors.add(inst.targets.get(0));
                    block.successors.add(inst.targets.get(1));
                }
                case "jmp" -> {
                    Inst inst = ir.emit(block, Opcode.JMP);
                    inst.targets.add(blockId(parts[1]));
                    block.successors.add(inst.targets.get(0));
                }
                case "ret" -> {
                    Inst inst = ir.emit(block, Opcode.RET);
                    inst.srcs.add(reg(parts[1]));
                }
                case "print" -> {
                    Inst inst = ir.emit(block, Opcode.PRINT);
                    inst.srcs.add(reg(parts[1]));
                }
                default -> throw new IllegalArgumentException("非法指令: " + line);
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("非法指令: " + line, e);
        }
    }

    private static int reg(String token) {
        if (!token.startsWith("%")) {
            throw new IllegalArgumentException("期望寄存器: " + token);
        }
        return Integer.parseInt(token.substring(1));
    }

    private static int blockId(String token) {
        if (!token.startsWith("b")) {
            throw new IllegalArgumentException("期望块: " + token);
        }
        return Integer.parseInt(token.substring(1));
    }

    /** Builder：SSA 构建助手（每次产出新寄存器保证单赋值，工单 0838 CU1） */
    public static final class Builder {
        public final Ir ir = new Ir();
        public Block current = ir.newBlock();

        private Inst emit(Opcode op) {
            return ir.emit(current, op);
        }

        public int constOf(long value) {
            Inst inst = emit(Opcode.CONST);
            inst.dest = ir.newReg();
            inst.constValue = value;
            return inst.dest;
        }

        public int binOp(Opcode op, int a, int b) {
            Inst inst = emit(op);
            inst.dest = ir.newReg();
            inst.srcs.add(a);
            inst.srcs.add(b);
            return inst.dest;
        }

        public void branch(int cond, int thenBlock, int elseBlock) {
            Inst inst = emit(Opcode.BR);
            inst.srcs.add(cond);
            inst.targets.add(thenBlock);
            inst.targets.add(elseBlock);
            current.successors.add(thenBlock);
            current.successors.add(elseBlock);
        }

        public void jump(int target) {
            Inst inst = emit(Opcode.JMP);
            inst.targets.add(target);
            current.successors.add(target);
        }

        public void ret(int reg) {
            Inst inst = emit(Opcode.RET);
            inst.srcs.add(reg);
        }

        public void print(int reg) {
            Inst inst = emit(Opcode.PRINT);
            inst.srcs.add(reg);
        }

        public Block newBlock() {
            current = ir.newBlock();
            return current;
        }
    }
}
