package cn.chyuan.ai.domain.querykernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量化扫描与选择向量（工单 0536 BL4，duckdb 列式批思想）。
 * 列式内存批（行×列）/谓词过滤产出选择向量/选择向量物化（按命中重组）/批行数与命中统计/
 * 空批合法。行以 List&lt;Object&gt; 表示，与 ExprEval 对齐。
 */
public final class ColumnBatch {

    private final List<String> names;
    private final List<List<Object>> columns;

    public ColumnBatch(List<String> names, List<List<Object>> columns) {
        if (names.size() != columns.size()) {
            throw new IllegalArgumentException("列名数与列数不一致");
        }
        int rows = columns.isEmpty() ? 0 : columns.get(0).size();
        for (List<Object> col : columns) {
            if (col.size() != rows) {
                throw new IllegalArgumentException("列长不一致");
            }
        }
        this.names = List.copyOf(names);
        this.columns = columns;
    }

    public List<String> names() {
        return names;
    }

    public int rowCount() {
        return columns.isEmpty() ? 0 : columns.get(0).size();
    }

    public int columnCount() {
        return columns.size();
    }

    /** 第 i 列（不可变视图） */
    public List<Object> column(int idx) {
        return columns.get(idx);
    }

    /** 第 r 行（列值列表，惰性拼装） */
    public List<Object> row(int r) {
        List<Object> row = new ArrayList<>(columns.size());
        for (List<Object> col : columns) {
            row.add(col.get(r));
        }
        return row;
    }

    /** 选择向量物化：按命中行号重组新批（零拷贝切片语义——引用复用不复制列值） */
    public ColumnBatch materialize(List<Integer> selection) {
        List<List<Object>> out = new ArrayList<>(columns.size());
        for (List<Object> col : columns) {
            List<Object> picked = new ArrayList<>(selection.size());
            for (Integer r : selection) {
                picked.add(col.get(r));
            }
            out.add(picked);
        }
        return new ColumnBatch(names, out);
    }

    /**
     * 谓词过滤：返回选择向量（命中行号升序）。TRUE 命中；FALSE/NULL 不命中
     * （三值逻辑下 UNKNOWN 不通过）。
     */
    public List<Integer> select(ExprEval.Expr predicate, ExprEval eval) {
        List<Integer> selection = new ArrayList<>();
        for (int r = 0; r < rowCount(); r++) {
            if (Boolean.TRUE.equals(eval.eval(predicate, row(r)))) {
                selection.add(r);
            }
        }
        return selection;
    }

    /** 空批（同名同列数、零行） */
    public static ColumnBatch empty(List<String> names) {
        List<List<Object>> cols = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            cols.add(new ArrayList<>());
        }
        return new ColumnBatch(names, cols);
    }
}
