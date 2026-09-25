package cn.chyuan.ai.domain.arrowkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RecordBatch（工单 0781 CN5，arrow 思想）。
 * 按 schema 组装多列/行数一致性校验/切片与投影共享底层（zero-copy 语义）/非空字段空值拒绝。
 */
public final class RecordBatch {

    private final ArrowSchema schema;
    private final List<Object> columns;
    private final int rows;

    private RecordBatch(ArrowSchema schema, List<Object> columns, int rows) {
        this.schema = schema;
        this.columns = columns;
        this.rows = rows;
    }

    public ArrowSchema schema() {
        return schema;
    }

    public int rows() {
        return rows;
    }

    public int columns() {
        return columns.size();
    }

    /** 取列：INT64/FP64/BOOL 返回定宽向量，UTF8 返回变长向量 */
    public ColumnVector fixedColumn(String name) {
        if (schema.field(name).type() == ArrowSchema.Type.UTF8) {
            throw new IllegalArgumentException("UTF8 列用 varLenColumn: " + name);
        }
        return (ColumnVector) columns.get(schema.indexOf(name));
    }

    public VarLenVector varLenColumn(String name) {
        if (schema.field(name).type() != ArrowSchema.Type.UTF8) {
            throw new IllegalArgumentException("非 UTF8 列用 fixedColumn: " + name);
        }
        return (VarLenVector) columns.get(schema.indexOf(name));
    }

    /** 单元格读取（空值 null） */
    public Object cell(String name, int row) {
        ArrowSchema.Field f = schema.field(name);
        if (f.type() == ArrowSchema.Type.UTF8) {
            return varLenColumn(name).get(row);
        }
        return fixedColumn(name).get(row);
    }

    /** 切片：各列视图共享底层数组 */
    public RecordBatch slice(int start, int len) {
        if (start < 0 || len < 0 || start + len > rows) {
            throw new IllegalArgumentException("批切片越界: [" + start + "," + len + ")/" + rows);
        }
        List<Object> sliced = new ArrayList<>();
        for (Object c : columns) {
            if (c instanceof ColumnVector v) {
                sliced.add(v.slice(start, len));
            } else {
                sliced.add(((VarLenVector) c).slice(start, len));
            }
        }
        return new RecordBatch(schema, sliced, len);
    }

    /** 投影：引用共享原列 */
    public RecordBatch project(List<String> names) {
        ArrowSchema projected = schema.project(names);
        List<Object> cols = new ArrayList<>();
        for (ArrowSchema.Field f : projected.fields()) {
            cols.add(columns.get(schema.indexOf(f.name())));
        }
        return new RecordBatch(projected, cols, rows);
    }

    /** 值级等价（schema 等价 + 逐格相等） */
    public boolean valueEquals(RecordBatch other) {
        if (!schema.equivalent(other.schema) || rows != other.rows) {
            return false;
        }
        for (ArrowSchema.Field f : schema.fields()) {
            for (int r = 0; r < rows; r++) {
                Object a = cell(f.name(), r);
                Object b = other.cell(f.name(), r);
                if (a == null ? b != null : !a.equals(b)) {
                    return false;
                }
            }
        }
        return true;
    }

    public static Builder builder(ArrowSchema schema) {
        return new Builder(schema);
    }

    public static final class Builder {
        private final ArrowSchema schema;
        private final List<ColumnVector.Builder> fixed;
        private final List<VarLenVector.Builder> varLen;
        private int rows = 0;

        private Builder(ArrowSchema schema) {
            this.schema = schema;
            this.fixed = new ArrayList<>();
            this.varLen = new ArrayList<>();
            for (ArrowSchema.Field f : schema.fields()) {
                if (f.type() == ArrowSchema.Type.UTF8) {
                    varLen.add(VarLenVector.builder(16));
                    fixed.add(null);
                } else {
                    fixed.add(ColumnVector.builder(f.type(), 16));
                    varLen.add(null);
                }
            }
        }

        /** 行级追加：值个数/类型/非空约束校验 */
        public Builder appendRow(List<Object> values) {
            if (values.size() != schema.fields().size()) {
                throw new IllegalArgumentException("行宽不符: " + values.size());
            }
            for (int i = 0; i < values.size(); i++) {
                ArrowSchema.Field f = schema.fields().get(i);
                Object v = values.get(i);
                if (v == null) {
                    if (!f.nullable()) {
                        throw new IllegalArgumentException("非空字段空值: " + f.name());
                    }
                    appendNull(i);
                    continue;
                }
                switch (f.type()) {
                    case INT64 -> fixed.get(i).appendLong(require(v, Long.class, f));
                    case FP64 -> fixed.get(i).appendDouble(require(v, Double.class, f));
                    case BOOL -> fixed.get(i).appendBool(require(v, Boolean.class, f));
                    case UTF8 -> varLen.get(i).appendString(require(v, String.class, f));
                }
            }
            rows++;
            return this;
        }

        public Builder appendRows(List<Map<String, Object>> rowsAsMaps) {
            rowsAsMaps.forEach(m -> {
                List<Object> row = new ArrayList<>();
                for (ArrowSchema.Field f : schema.fields()) {
                    row.add(m.get(f.name()));
                }
                appendRow(row);
            });
            return this;
        }

        private void appendNull(int i) {
            if (fixed.get(i) != null) {
                fixed.get(i).appendNull();
            } else {
                varLen.get(i).appendNull();
            }
        }

        private <T> T require(Object v, Class<T> clazz, ArrowSchema.Field f) {
            if (!clazz.isInstance(v)) {
                throw new IllegalArgumentException("类型错配: " + f.name() + " 期望 " + clazz.getSimpleName());
            }
            return clazz.cast(v);
        }

        /** 行数一致性由同源追加保证；出批 */
        public RecordBatch build() {
            return new RecordBatch(schema, new ArrayList<>(mergeColumns()), rows);
        }

        private List<Object> mergeColumns() {
            List<Object> cols = new ArrayList<>();
            for (int i = 0; i < schema.fields().size(); i++) {
                cols.add(fixed.get(i) != null ? fixed.get(i).build() : varLen.get(i).build());
            }
            return cols;
        }
    }
}
