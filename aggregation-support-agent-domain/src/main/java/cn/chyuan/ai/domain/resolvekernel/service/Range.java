package cn.chyuan.ai.domain.resolvekernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 版本约束与区间（工单 0664 CA2，uv 思想）。
 * 约束算子 ==/!=/>=/<=/>/</~/^（裸版本视作 ==，* 全匹配）/
 * satisfiedBy 精确判定（含 != 空洞）/区间求交与空集检测/
 * 多约束规范化合并（有序不重叠区间清单）。
 */
public final class Range {

    /** 区间边界：版本+是否含端 */
    public record Bound(Semver version, boolean inclusive) implements Comparable<Bound> {
        @Override
        public int compareTo(Bound o) {
            int c = version.compareTo(o.version);
            if (c != 0) {
                return c;
            }
            return inclusive == o.inclusive ? 0 : (inclusive ? -1 : 1);
        }
    }

    /** 单条约束：算子+锚版本 */
    public record Constraint(String op, Semver anchor) {

        public boolean satisfiedBy(Semver v) {
            return switch (op) {
                case "==" -> v.compareTo(anchor) == 0;
                case "!=" -> v.compareTo(anchor) != 0;
                case ">=" -> v.compareTo(anchor) >= 0;
                case "<=" -> v.compareTo(anchor) <= 0;
                case ">" -> v.compareTo(anchor) > 0;
                case "<" -> v.compareTo(anchor) < 0;
                case "^" -> v.compareTo(anchor) >= 0 && v.compareTo(caretUpper(anchor)) < 0;
                case "~" -> v.compareTo(anchor) >= 0 && v.compareTo(tildeUpper(anchor)) < 0;
                default -> throw new IllegalStateException("未知算子 " + op);
            };
        }

        /** 区间近似（!= 产生空洞，区间分析按全量处理，精确判定仍走 satisfiedBy） */
        Bound lo() {
            return switch (op) {
                case "==" -> new Bound(anchor, true);
                case "!=" -> new Bound(Semver.MIN, true);
                case ">=" -> new Bound(anchor, true);
                case ">" -> new Bound(anchor, false);
                case "<", "<=" -> new Bound(Semver.MIN, true);
                case "^", "~" -> new Bound(anchor, true);
                default -> throw new IllegalStateException("未知算子 " + op);
            };
        }

        Bound hi() {
            return switch (op) {
                case "==" -> new Bound(anchor, true);
                case "!=" -> new Bound(Semver.MAX, true);
                case ">=", ">" -> new Bound(Semver.MAX, true);
                case "<=" -> new Bound(anchor, true);
                case "<" -> new Bound(anchor, false);
                case "^" -> new Bound(caretUpper(anchor), false);
                case "~" -> new Bound(tildeUpper(anchor), false);
                default -> throw new IllegalStateException("未知算子 " + op);
            };
        }

        static Semver caretUpper(Semver v) {
            if (v.major() > 0) {
                return new Semver(v.major() + 1, 0, 0, List.of(), "");
            }
            if (v.minor() > 0) {
                return new Semver(0, v.minor() + 1, 0, List.of(), "");
            }
            return new Semver(0, 0, v.patch() + 1, List.of(), "");
        }

        static Semver tildeUpper(Semver v) {
            return new Semver(v.major(), v.minor() + 1, 0, List.of(), "");
        }

        public static Constraint parse(String text) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("约束不得为空");
            }
            String t = text.trim();
            for (String op : new String[]{">=", "<=", "==", "!=", "~", "^", ">", "<"}) {
                if (t.startsWith(op)) {
                    return new Constraint(op, Semver.of(t.substring(op.length())));
                }
            }
            if (t.equals("*")) {
                return new Constraint(">=", Semver.MIN);
            }
            return new Constraint("==", Semver.of(t));
        }
    }

    private final List<Constraint> constraints;

    private Range(List<Constraint> constraints) {
        this.constraints = List.copyOf(constraints);
    }

    public static Range of(String... specs) {
        if (specs == null || specs.length == 0) {
            throw new IllegalArgumentException("约束清单不得为空");
        }
        List<Constraint> list = new ArrayList<>();
        for (String spec : specs) {
            if (spec == null) {
                throw new IllegalArgumentException("约束不得为 null");
            }
            for (String part : spec.trim().split("[,\\s]+")) {
                if (!part.isEmpty()) {
                    list.add(Constraint.parse(part));
                }
            }
        }
        if (list.isEmpty()) {
            throw new IllegalArgumentException("约束清单不得为空");
        }
        return new Range(list);
    }

    public List<Constraint> constraints() {
        return constraints;
    }

    public boolean satisfiedBy(Semver v) {
        for (Constraint c : constraints) {
            if (!c.satisfiedBy(v)) {
                return false;
            }
        }
        return true;
    }

    /** 交集：约束合并（判定语义），区间分析用于空集检测 */
    public Range intersect(Range o) {
        if (o == null) {
            throw new IllegalArgumentException("交集对象不得为 null");
        }
        List<Constraint> merged = new ArrayList<>(constraints);
        merged.addAll(o.constraints);
        return new Range(merged);
    }

    /** 空集检测（区间近似口径：!= 空洞不计入空性，仅由区间上下界判定） */
    public boolean isEmpty() {
        Bound lo = new Bound(Semver.MIN, true);
        Bound hi = new Bound(Semver.MAX, true);
        for (Constraint c : constraints) {
            if (c.lo().compareTo(lo) > 0) {
                lo = c.lo();
            }
            if (c.hi().compareTo(hi) < 0) {
                hi = c.hi();
            }
        }
        int c = lo.version().compareTo(hi.version());
        return c > 0 || (c == 0 && !(lo.inclusive() && hi.inclusive()));
    }

    /** 规范化合并：全部约束的区间求交为单段 [max-lo, min-hi]（!= 空洞由 satisfiedBy 精确判定），空集返回空清单 */
    public List<Bound[]> normalized() {
        Bound lo = new Bound(Semver.MIN, true);
        Bound hi = new Bound(Semver.MAX, true);
        for (Constraint c : constraints) {
            if (c.lo().compareTo(lo) > 0) {
                lo = c.lo();
            }
            if (c.hi().compareTo(hi) < 0) {
                hi = c.hi();
            }
        }
        int c = lo.version().compareTo(hi.version());
        if (c > 0 || (c == 0 && !(lo.inclusive() && hi.inclusive()))) {
            return List.of();
        }
        return List.<Bound[]>of(new Bound[]{lo, hi});
    }

    @Override
    public String toString() {
        List<String> parts = new ArrayList<>();
        for (Constraint c : constraints) {
            parts.add(c.op() + c.anchor());
        }
        return String.join(" , ", parts);
    }
}
