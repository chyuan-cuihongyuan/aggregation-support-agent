package cn.chyuan.ai.domain.ownkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 借用检查内核（工单 0830-0836 CT1-CT7，rust 借用检查思想）。
 * 所有权 move 与失效/共享可变借用/NLL 活跃区间/冲突诊断/作用域栈/再借出/生命周期区间表与悬垂检测。
 */
public final class OwnChecker {

    /** 诊断（scankernel 风格输出形状，工单 0833 CT4） */
    public record Diagnostic(String code, String message, int line) {
    }

    /** 借出（借用）：mut=可变，end 为 NLL 末次使用行（null=仍活跃） */
    public static final class Loan {
        public final String name;
        public final boolean mutable;
        public final int from;
        public Integer end;
        public final int scopeDepth;

        Loan(String name, boolean mutable, int from, int scopeDepth) {
            this.name = name;
            this.mutable = mutable;
            this.from = from;
            this.scopeDepth = scopeDepth;
        }

        public boolean live() {
            return end == null;
        }
    }

    /** 生命周期区间（工单 0836 CT7） */
    public record Region(String name, int birth, int death) {
        public boolean overlaps(Region other) {
            return birth <= other.death && other.birth <= death;
        }
    }

    private static final class Binding {
        String var;
        int definedAt = -1;
        boolean moved = false;
        int movedAt = -1;
        boolean dropped = false;
        final List<Loan> loans = new ArrayList<>();
    }

    private final Map<String, Binding> bindings = new LinkedHashMap<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final List<String> scopeStack = new ArrayList<>();
    private int depth = 0;

    public List<Diagnostic> diagnostics() {
        return List.copyOf(diagnostics);
    }

    /** CT1：定义绑定（未初始化使用的前置） */
    public OwnChecker define(String var, int line) {
        Binding b = new Binding();
        b.var = var;
        b.definedAt = line;
        bindings.put(var, b);
        return this;
    }

    /** CT1：clone 豁免——深拷贝出新绑定，源不受影响 */
    public OwnChecker cloneInto(String src, String fresh, int line) {
        Binding from = requireDefined(src, line);
        if (from == null) {
            return this;
        }
        return define(fresh, line);
    }

    /** CT1：move——源失效；活跃借出或已 move 拒绝 */
    public OwnChecker moveFrom(String src, String into, int line) {
        Binding b = requireDefined(src, line);
        if (b == null) {
            return this;
        }
        if (b.moved) {
            diag("E0382", "use of moved value '" + src + "'", line);
        }
        if (b.dropped) {
            diag("E0382", "use of dropped value '" + src + "'", line);
            return this;
        }
        Loan live = liveLoan(b);
        if (live != null) {
            diag("E0507", "cannot move out of '" + src + "' because it is borrowed by '" + live.name + "'", line);
        }
        if (!b.moved) {
            b.moved = true;
            b.movedAt = line;
            define(into, line);
        }
        return this;
    }

    /** CT1：显式 drop（double-free 语义） */
    public OwnChecker drop(String var, int line) {
        Binding b = requireDefined(var, line);
        if (b == null) {
            return this;
        }
        if (b.dropped || b.moved) {
            diag("E0494", "double drop of '" + var + "'", line);
            return this;
        }
        b.dropped = true;
        return this;
    }

    /** 使用：moved/dropped 即 E0382；活跃可变借出即 E0502 */
    public OwnChecker use(String var, int line) {
        Binding b = requireDefined(var, line);
        if (b == null) {
            return this;
        }
        if (b.moved) {
            diag("E0382", "borrow of moved value '" + var + "' (moved at " + b.movedAt + ")", line);
            return this;
        }
        if (b.dropped) {
            diag("E0382", "use of dropped value '" + var + "'", line);
            return this;
        }
        Loan mut = liveMutable(b);
        if (mut != null) {
            diag("E0502", "cannot use '" + var + "' because it is mutably borrowed by '" + mut.name + "'", line);
        }
        return this;
    }

    /** CT2：共享借用——多条并存；与活跃可变借出冲突 E0502 */
    public OwnChecker borrowShared(String var, String loanName, int line) {
        Binding b = requireDefined(var, line);
        if (b == null) {
            return this;
        }
        Loan mut = liveMutable(b);
        if (mut != null) {
            diag("E0502", "cannot borrow '" + var + "' as shared because it is also borrowed as mutable by '" + mut.name + "'", line);
            return this;
        }
        if (b.moved) {
            diag("E0382", "borrow of moved value '" + var + "'", line);
            return this;
        }
        b.loans.add(new Loan(loanName, false, line, depth));
        return this;
    }

    /** CT2/CT6：可变借用——须独占；活跃借出存在即 E0499 */
    public OwnChecker borrowMut(String var, String loanName, int line) {
        Binding b = requireDefined(var, line);
        if (b == null) {
            return this;
        }
        if (b.moved) {
            diag("E0382", "borrow of moved value '" + var + "'", line);
            return this;
        }
        Loan any = liveLoan(b);
        if (any != null) {
            diag("E0499", "cannot borrow '" + var + "' as mutable more than once (by '" + loanName + "' vs '" + any.name + "')", line);
            return this;
        }
        b.loans.add(new Loan(loanName, true, line, depth));
        return this;
    }

    /** CT3：NLL——借出末次使用后区间终结 */
    public OwnChecker loanEnd(String loanName, int line) {
        for (Binding b : bindings.values()) {
            for (Loan loan : b.loans) {
                if (loan.name.equals(loanName) && loan.live()) {
                    loan.end = line;
                    return this;
                }
            }
        }
        diag("E0000", "unknown loan '" + loanName + "'", line);
        return this;
    }

    /** CT6：可变借用再借出（reborrow）——由活跃可变借出派生共享借出 */
    public OwnChecker reborrow(String loanName, String freshName, int line) {
        for (Binding b : bindings.values()) {
            for (Loan loan : b.loans) {
                if (loan.name.equals(loanName) && loan.live()) {
                    if (!loan.mutable) {
                        diag("E0000", "reborrow requires mutable loan '" + loanName + "'", line);
                        return this;
                    }
                    b.loans.add(new Loan(freshName, true, line, depth));
                    return this;
                }
            }
        }
        diag("E0000", "unknown loan '" + loanName + "'", line);
        return this;
    }

    /** CT5：作用域入栈/出栈——出栈终结该层借出 */
    public OwnChecker scopePush(String label) {
        depth++;
        scopeStack.add(label);
        return this;
    }

    public OwnChecker scopePop() {
        if (depth > 0) {
            for (Binding b : bindings.values()) {
                for (Loan loan : b.loans) {
                    if (loan.live() && loan.scopeDepth == depth) {
                        loan.end = Integer.MAX_VALUE;
                    }
                }
            }
            depth--;
            scopeStack.remove(scopeStack.size() - 1);
        }
        return this;
    }

    /** CT7：生命周期区间表与悬垂检测——返回借出须宿主仍在作用域 */
    public OwnChecker returnBorrow(String loanName, int line) {
        for (Binding b : bindings.values()) {
            for (Loan loan : b.loans) {
                if (loan.name.equals(loanName)) {
                    if (loan.scopeDepth > depth || b.dropped || b.moved) {
                        diag("E0515", "cannot return reference to '" + b.var + "' (dangling)", line);
                    }
                    return this;
                }
            }
        }
        diag("E0000", "unknown loan '" + loanName + "'", line);
        return this;
    }

    /** 区间表：指定绑定全部借出区间 */
    public List<Region> regions(String var) {
        List<Region> out = new ArrayList<>();
        Binding b = bindings.get(var);
        if (b != null) {
            for (Loan loan : b.loans) {
                out.add(new Region(loan.name, loan.from, loan.end == null ? Integer.MAX_VALUE : loan.end));
            }
        }
        return out;
    }

    public boolean conflicts(String varA, String varB) {
        for (Region a : regions(varA)) {
            for (Region r : regions(varB)) {
                if (a.overlaps(r)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Loan liveLoan(Binding b) {
        for (Loan loan : b.loans) {
            if (loan.live()) {
                return loan;
            }
        }
        return null;
    }

    private Loan liveMutable(Binding b) {
        for (Loan loan : b.loans) {
            if (loan.live() && loan.mutable) {
                return loan;
            }
        }
        return null;
    }

    private Binding requireDefined(String var, int line) {
        Binding b = bindings.get(var);
        if (b == null) {
            diag("E0425", "uninitialized value '" + var + "'", line);
        }
        return b;
    }

    private void diag(String code, String message, int line) {
        diagnostics.add(new Diagnostic(code, message, line));
    }
}
