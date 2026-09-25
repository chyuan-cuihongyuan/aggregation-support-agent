package cn.chyuan.ai.domain.arrowkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 列式内存端口（工单 0784 CN8，arrow 思想）。
 * batch·投影·算子·序列化入口统一编排/与 querykernel 只读联动（行集组装 record batch 形态，泛型入参不 import）/
 * arrow-kernel.enabled 默认关（开启才改变行为）。
 */
public interface ArrowPort {

    /** 行集 → record batch（querykernel 行集只读联动形态：行形状数据不 import querykernel） */
    RecordBatch fromRows(ArrowSchema schema, List<Map<String, Object>> rows);

    /** 投影 */
    RecordBatch project(RecordBatch batch, List<String> names);

    /** 列聚合 */
    Object aggregate(RecordBatch batch, String column, ColumnOps.Agg agg);

    /** 冻结（IPC 序列化） */
    byte[] freeze(RecordBatch batch);

    /** 解冻（校验拒绝损坏） */
    RecordBatch thaw(byte[] bytes);

    static ArrowPort inMemory() {
        return new InMemoryArrow();
    }
}

final class InMemoryArrow implements ArrowPort {

    @Override
    public RecordBatch fromRows(ArrowSchema schema, List<Map<String, Object>> rows) {
        return RecordBatch.builder(schema).appendRows(rows).build();
    }

    @Override
    public RecordBatch project(RecordBatch batch, List<String> names) {
        return batch.project(names);
    }

    @Override
    public Object aggregate(RecordBatch batch, String column, ColumnOps.Agg agg) {
        ArrowSchema.Field f = batch.schema().field(column);
        if (f.type() == ArrowSchema.Type.UTF8) {
            return ColumnOps.aggregate(batch.varLenColumn(column), agg);
        }
        return ColumnOps.aggregate(batch.fixedColumn(column), agg);
    }

    @Override
    public byte[] freeze(RecordBatch batch) {
        return ArrowIpc.serialize(batch);
    }

    @Override
    public RecordBatch thaw(byte[] bytes) {
        return ArrowIpc.deserialize(bytes);
    }
}
