package cn.chyuan.ai.domain.arrowkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Schema 与字段类型（工单 0777 CN1，arrow 思想）。
 * 字段名/类型 INT64·FP64·UTF8·BOOL/可空标记/schema 等价与投影/重复字段拒绝。
 */
public record ArrowSchema(List<Field> fields) {

    public enum Type { INT64, FP64, UTF8, BOOL }

    public record Field(String name, Type type, boolean nullable) {
        public Field {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("字段名缺失");
            }
        }
    }

    public ArrowSchema {
        fields = List.copyOf(fields);
        Set<String> seen = new java.util.HashSet<>();
        for (Field f : fields) {
            if (!seen.add(f.name())) {
                throw new IllegalArgumentException("重复字段: " + f.name());
            }
        }
    }

    public static ArrowSchema of(Field... fields) {
        return new ArrowSchema(List.of(fields));
    }

    public int indexOf(String name) {
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).name().equals(name)) {
                return i;
            }
        }
        throw new IllegalArgumentException("未知字段: " + name);
    }

    public Field field(String name) {
        return fields.get(indexOf(name));
    }

    /** 等价：字段名/类型/可空全同且有序 */
    public boolean equivalent(ArrowSchema other) {
        return fields.equals(other.fields);
    }

    /** 投影：按名取子集（未知字段拒绝），列序随投影序 */
    public ArrowSchema project(List<String> names) {
        List<Field> out = new ArrayList<>();
        for (String n : names) {
            out.add(field(n));
        }
        return new ArrowSchema(out);
    }
}
