package cn.chyuan.ai.domain.tuningkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 搜索空间（工单 0810 CR2，optuna 思想）。
 * int·float 线性与 log/categorical 分布/越界与空空间拒绝/种子确定性采样/空间快照。
 */
public final class Space {

    public sealed interface Param permits IntP, FloatP, CatP {
    }

    public record IntP(String name, long low, long high, boolean log) implements Param {
        public IntP {
            if (low > high) {
                throw new IllegalArgumentException("int 下界大于上界: " + name);
            }
            if (log && low <= 0) {
                throw new IllegalArgumentException("log int 下界须为正: " + name);
            }
        }
    }

    public record FloatP(String name, double low, double high, boolean log) implements Param {
        public FloatP {
            if (low > high) {
                throw new IllegalArgumentException("float 下界大于上界: " + name);
            }
            if (log && low <= 0) {
                throw new IllegalArgumentException("log float 下界须为正: " + name);
            }
        }
    }

    public record CatP(String name, List<Object> choices) implements Param {
        public CatP {
            choices = List.copyOf(choices);
            if (choices.isEmpty()) {
                throw new IllegalArgumentException("categorical 空选择: " + name);
            }
        }
    }

    private final Map<String, Param> params = new LinkedHashMap<>();

    public Space add(Param p) {
        if (params.containsKey(p instanceof IntP i ? i.name() : p instanceof FloatP f ? f.name()
                : ((CatP) p).name())) {
            throw new IllegalArgumentException("重复参数名");
        }
        params.put(nameOf(p), p);
        return this;
    }

    public static String nameOf(Param p) {
        return switch (p) {
            case IntP i -> i.name();
            case FloatP f -> f.name();
            case CatP c -> c.name();
        };
    }

    public int size() {
        return params.size();
    }

    public Map<String, Param> snapshot() {
        return Map.copyOf(params);
    }

    /** 确定性采样（种子随机源） */
    public Map<String, Object> sample(Random random) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Param p : params.values()) {
            out.put(nameOf(p), sampleOne(p, random));
        }
        return out;
    }

    static Object sampleOne(Param p, Random random) {
        switch (p) {
            case IntP i -> {
                if (i.log()) {
                    double lo = Math.log(i.low());
                    double hi = Math.log(i.high() + 1);
                    long v = (long) Math.floor(Math.exp(lo + (hi - lo) * random.nextDouble()));
                    return Math.max(i.low(), Math.min(i.high(), v));
                }
                return i.low() + (long) (random.nextDouble() * (i.high() - i.low() + 1));
            }
            case FloatP f -> {
                if (f.log()) {
                    double lo = Math.log(f.low());
                    double hi = Math.log(f.high());
                    return Math.exp(lo + (hi - lo) * random.nextDouble());
                }
                return f.low() + (f.high() - f.low()) * random.nextDouble();
            }
            case CatP c -> {
                return c.choices().get(random.nextInt(c.choices().size()));
            }
        }
    }

    /** 参数值域内校验（TPE 候选过滤用） */
    public static boolean inBounds(Param p, Object value) {
        switch (p) {
            case IntP i -> {
                if (!(value instanceof Long v)) {
                    return false;
                }
                return v >= i.low() && v <= i.high();
            }
            case FloatP f -> {
                if (!(value instanceof Double v)) {
                    return false;
                }
                return v >= f.low() && v <= f.high();
            }
            case CatP c -> {
                return c.choices().contains(value);
            }
        }
    }

    public List<String> names() {
        return new ArrayList<>(params.keySet());
    }

    public Param param(String name) {
        return params.get(name);
    }
}
