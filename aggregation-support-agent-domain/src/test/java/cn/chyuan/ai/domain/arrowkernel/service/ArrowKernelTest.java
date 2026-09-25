package cn.chyuan.ai.domain.arrowkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 列式内存内核测试（工单 0777-0784 CN1-CN8，arrow 思想）。
 * Schema/Validity 位图/定宽与变长向量/批切片投影/列算子/IPC 简化封装/端口编排。
 */
class ArrowKernelTest {

    private static final ArrowSchema SCHEMA = ArrowSchema.of(
            new ArrowSchema.Field("id", ArrowSchema.Type.INT64, false),
            new ArrowSchema.Field("score", ArrowSchema.Type.FP64, true),
            new ArrowSchema.Field("name", ArrowSchema.Type.UTF8, true),
            new ArrowSchema.Field("ok", ArrowSchema.Type.BOOL, true));

    @Test
    void schemaFieldsAndRejects() {
        assertEquals(4, SCHEMA.fields().size());
        assertEquals(ArrowSchema.Type.INT64, SCHEMA.field("id").type());
        assertTrue(SCHEMA.equivalent(ArrowSchema.of(SCHEMA.fields().toArray(new ArrowSchema.Field[0]))));
        assertFalse(SCHEMA.equivalent(SCHEMA.project(List.of("id", "score"))), "等价要求同列数");
        ArrowSchema projected = SCHEMA.project(List.of("score", "id"));
        assertEquals(List.of("score", "id"), projected.fields().stream().map(ArrowSchema.Field::name).toList(),
                "投影列序随投影序");
        assertThrows(IllegalArgumentException.class,
                () -> ArrowSchema.of(new ArrowSchema.Field("a", ArrowSchema.Type.INT64, true),
                        new ArrowSchema.Field("a", ArrowSchema.Type.INT64, true)), "重复字段拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> new ArrowSchema.Field("", ArrowSchema.Type.INT64, true), "空名拒绝");
        assertThrows(IllegalArgumentException.class, () -> SCHEMA.project(List.of("nope")), "未知投影拒绝");
    }

    @Test
    void validityBitBasics() {
        ValidityBitmap.Builder b = ValidityBitmap.builder(4);
        b.appendValid();
        b.appendNull();
        b.appendValid();
        ValidityBitmap bits = b.build();
        assertTrue(bits.isValid(0));
        assertFalse(bits.isValid(1));
        assertEquals(1, bits.nullCount());
        assertFalse(bits.noneNull());
        assertFalse(bits.allNull());
        b.set(1);
        assertEquals(0, b.build().nullCount(), "事后置位");
        assertTrue(b.build().noneNull());
        b.clear(0);
        b.clear(1);
        b.clear(2);
        assertTrue(b.build().allNull());
        assertThrows(IllegalArgumentException.class, () -> bits.isValid(3), "越界拒绝");
        assertThrows(IllegalArgumentException.class, () -> bits.isValid(-1));
    }

    @Test
    void validityPackedRoundTripAndFastPath() {
        ValidityBitmap allValid = ValidityBitmap.allValid(70);
        assertTrue(allValid.noneNull(), "全非空快速路径");
        assertTrue(ValidityBitmap.allNull(5).allNull());
        byte[] packed = allValid.toPackedBits();
        for (int i = 0; i < 70; i++) {
            assertEquals(allValid.isValid(i), ValidityBitmap.fromPackedBits(packed, 70).isValid(i));
        }
        assertThrows(IllegalArgumentException.class,
                () -> ValidityBitmap.fromPackedBits(new byte[1], 70), "位图数据不足拒绝");
    }

    @Test
    void fixedVectorAppendRead() {
        ColumnVector ints = ColumnVector.builder(ArrowSchema.Type.INT64, 4).appendLong(1).appendLong(2).build();
        assertEquals(2, ints.length());
        assertEquals(1L, ints.getLong(0));
        assertThrows(IllegalArgumentException.class, () -> ints.getLong(5), "越界拒绝");
        ColumnVector fp = ColumnVector.builder(ArrowSchema.Type.FP64, 2).appendDouble(1.5).appendNull().build();
        assertNull(fp.get(1));
        assertTrue(fp.isNull(1));
        assertThrows(IllegalArgumentException.class, () -> fp.getLong(0), "类型错配拒绝");
        ColumnVector bools = ColumnVector.builder(ArrowSchema.Type.BOOL, 2).appendBool(true).build();
        assertEquals(Boolean.TRUE, bools.get(0));
        assertThrows(IllegalArgumentException.class,
                () -> ColumnVector.builder(ArrowSchema.Type.UTF8, 2), "UTF8 不入定宽");
    }

    @Test
    void varLenVectorBasics() {
        VarLenVector.Builder b = VarLenVector.builder(4);
        b.appendString("alpha").appendString("").appendNull().appendString("中文λ");
        VarLenVector v = b.build();
        assertEquals("alpha", v.get(0));
        assertEquals("", v.get(1), "空串非 null");
        assertNull(v.get(2), "null 位图标记");
        assertEquals("中文λ", v.get(3));
        assertFalse(v.isNull(1));
        assertTrue(v.isNull(2));
        VarLenVector window = v.slice(1, 2);
        assertEquals("", window.get(0));
        assertNull(window.get(1));
        assertThrows(IllegalArgumentException.class, () -> v.slice(2, 3), "切片越界");
    }

    @Test
    void recordBatchBuildAndConsistency() {
        RecordBatch.Builder b = RecordBatch.builder(SCHEMA);
        b.appendRow(java.util.Arrays.asList(1L, 1.5, "a", true));
        b.appendRow(java.util.Arrays.asList(2L, null, "", null));
        RecordBatch batch = b.build();
        assertEquals(2, batch.rows());
        assertEquals(1.5, (Double) batch.cell("score", 0));
        assertEquals("", batch.cell("name", 1));
        assertNull(batch.cell("ok", 1));
        assertThrows(IllegalArgumentException.class,
                () -> RecordBatch.builder(SCHEMA).appendRow(List.of(1L, 2.0)), "行宽不符拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> RecordBatch.builder(SCHEMA)
                        .appendRow(java.util.Arrays.asList(null, 2.0, "x", true)), "非空字段空值拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> RecordBatch.builder(SCHEMA).appendRow(List.of(1.5, 2.0, "x", true)), "类型错配拒绝");
    }

    @Test
    void batchSliceAndProject() {
        RecordBatch batch = sampleBatch();
        RecordBatch window = batch.slice(1, 2);
        assertEquals(2, window.rows());
        assertEquals(2L, window.cell("id", 0));
        assertNull(window.cell("name", 1));
        assertThrows(IllegalArgumentException.class, () -> batch.slice(2, 2), "批切片越界");
        RecordBatch projected = batch.project(List.of("name", "id"));
        assertEquals(List.of("name", "id"),
                projected.schema().fields().stream().map(ArrowSchema.Field::name).toList());
        assertEquals(3, projected.rows());
        assertThrows(IllegalArgumentException.class, () -> batch.project(List.of("zzz")));
    }

    @Test
    void arithmeticOps() {
        ColumnVector a = ints(1, 2, 3);
        ColumnVector b = ColumnVector.builder(ArrowSchema.Type.INT64, 3)
                .appendLong(10).appendNull().appendLong(30).build();
        ColumnVector sum = ColumnOps.arithmetic(a, b, ColumnOps.Arith.ADD);
        assertEquals(11L, sum.getLong(0));
        assertTrue(sum.isNull(1), "空传播");
        assertEquals(33L, sum.getLong(2));
        ColumnVector mul = ColumnOps.arithmetic(a, b, ColumnOps.Arith.MUL);
        assertEquals(90L, mul.getLong(2));
        ColumnVector fp = ColumnVector.builder(ArrowSchema.Type.FP64, 2).appendDouble(1.5).appendDouble(2.0).build();
        ColumnVector mixed = ColumnOps.arithmetic(fp, a.slice(0, 2), ColumnOps.Arith.ADD);
        assertEquals(ArrowSchema.Type.FP64, mixed.type(), "INT64+FP64 提升");
        assertEquals(2.5, mixed.getDouble(0));
        ColumnVector bools = ColumnVector.builder(ArrowSchema.Type.BOOL, 1).appendBool(true).build();
        assertThrows(IllegalArgumentException.class,
                () -> ColumnOps.arithmetic(a, bools, ColumnOps.Arith.ADD), "BOOL 算术拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> ColumnOps.arithmetic(a, a.slice(0, 1), ColumnOps.Arith.ADD), "列长不一致拒绝");
    }

    @Test
    void compareOps() {
        ColumnVector a = ints(1, 5, -1);
        ColumnVector b = ints(2, 5, 0);
        ColumnVector lt = ColumnOps.compare(a, b, ColumnOps.Cmp.LT);
        assertEquals(Boolean.TRUE, lt.get(0));
        assertEquals(Boolean.FALSE, lt.get(1));
        assertTrue(lt.isNull(2));
        ColumnVector eq = ColumnOps.compare(a, b, ColumnOps.Cmp.EQ);
        assertEquals(Boolean.TRUE, eq.get(1), "同值相等");
        ColumnVector small = ColumnVector.builder(ArrowSchema.Type.FP64, 1).appendDouble(0.5).build();
        ColumnVector cross = ColumnOps.compare(a.slice(0, 1), small, ColumnOps.Cmp.GT);
        assertEquals(Boolean.TRUE, cross.get(0), "整浮跨型比较");
        VarLenVector x = VarLenVector.builder(2).appendString("aa").appendString("b").build();
        VarLenVector y = VarLenVector.builder(2).appendString("ab").appendString("a").build();
        ColumnVector str = ColumnOps.compare(x, y, ColumnOps.Cmp.LT);
        assertEquals(Boolean.TRUE, str.get(0), "字典序");
        assertEquals(Boolean.FALSE, str.get(1));
    }

    @Test
    void aggregateOps() {
        ColumnVector v = ColumnVector.builder(ArrowSchema.Type.INT64, 3)
                .appendLong(1).appendNull().appendLong(3).build();
        assertEquals(4L, ColumnOps.aggregate(v, ColumnOps.Agg.SUM));
        assertEquals(1L, ColumnOps.aggregate(v, ColumnOps.Agg.MIN));
        assertEquals(3L, ColumnOps.aggregate(v, ColumnOps.Agg.MAX));
        assertEquals(2L, ColumnOps.aggregate(v, ColumnOps.Agg.COUNT), "COUNT 计非空");
        ColumnVector empty = ColumnVector.builder(ArrowSchema.Type.INT64, 1).build();
        assertEquals(0L, ColumnOps.aggregate(empty, ColumnOps.Agg.SUM), "空集 SUM 零值");
        assertNull(ColumnOps.aggregate(empty, ColumnOps.Agg.MIN), "空集 MIN null");
        VarLenVector names = VarLenVector.builder(3).appendString("b").appendNull().appendString("a").build();
        assertEquals("a", ColumnOps.aggregate(names, ColumnOps.Agg.MIN));
        assertEquals("b", ColumnOps.aggregate(names, ColumnOps.Agg.MAX));
        assertThrows(IllegalArgumentException.class,
                () -> ColumnOps.aggregate(names, ColumnOps.Agg.SUM), "UTF8 SUM 拒绝");
    }

    @Test
    void ipcRoundTripAndCorruption() {
        RecordBatch batch = sampleBatch();
        byte[] bytes = ArrowIpc.serialize(batch);
        assertTrue(ArrowIpc.deserialize(bytes).valueEquals(batch), "往返等价");
        byte[] badMagic = bytes.clone();
        badMagic[0] = 'X';
        assertThrows(IllegalArgumentException.class, () -> ArrowIpc.deserialize(badMagic), "魔数损坏拒绝");
        byte[] badCrc = bytes.clone();
        badCrc[badCrc.length - 1] ^= 0xFF;
        assertThrows(IllegalArgumentException.class, () -> ArrowIpc.deserialize(badCrc), "CRC 损坏拒绝");
        byte[] truncated = java.util.Arrays.copyOf(bytes, bytes.length / 2);
        assertThrows(IllegalArgumentException.class, () -> ArrowIpc.deserialize(truncated), "截断拒绝");
        RecordBatch empty = RecordBatch.builder(SCHEMA).build();
        assertTrue(ArrowIpc.deserialize(ArrowIpc.serialize(empty)).valueEquals(empty), "空批往返");
    }

    @Test
    void portOrchestrationAndRowLinkage() {
        ArrowPort port = ArrowPort.inMemory();
        List<Map<String, Object>> rows = List.of(
                Map.of("id", 1L, "score", 1.5, "name", "a"),
                Map.of("id", 2L, "name", "b"));
        RecordBatch batch = port.fromRows(SCHEMA, rows);
        assertEquals(2, batch.rows());
        assertNull(batch.cell("score", 1), "行集缺键即 null");
        RecordBatch projected = port.project(batch, List.of("id"));
        assertEquals(1, projected.columns());
        assertEquals(3L, port.aggregate(batch, "id", ColumnOps.Agg.SUM), "id 列求和");
        assertTrue(ArrowIpc.deserialize(port.freeze(batch)).valueEquals(batch), "freeze/thaw 往返");
    }

    private static ColumnVector ints(long... values) {
        ColumnVector.Builder b = ColumnVector.builder(ArrowSchema.Type.INT64, values.length);
        for (long v : values) {
            if (v == -1) {
                b.appendNull();
            } else {
                b.appendLong(v);
            }
        }
        return b.build();
    }

    private static RecordBatch sampleBatch() {
        RecordBatch.Builder b = RecordBatch.builder(SCHEMA);
        b.appendRow(java.util.Arrays.asList(1L, 1.5, "a", true));
        b.appendRow(java.util.Arrays.asList(2L, null, "", null));
        b.appendRow(java.util.Arrays.asList(3L, 2.5, null, false));
        return b.build();
    }
}
