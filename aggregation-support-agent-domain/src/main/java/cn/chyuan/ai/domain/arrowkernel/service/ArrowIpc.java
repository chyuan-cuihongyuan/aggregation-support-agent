package cn.chyuan.ai.domain.arrowkernel.service;

import java.io.ByteArrayOutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;

/**
 * IPC 简化序列化（工单 0783 CN7，arrow 思想）。
 * schema+批量二进制封装/魔数与长度前缀/CRC32 校验/往返等价/损坏拒绝（魔数·CRC·截断）。
 */
public final class ArrowIpc {

    private static final byte[] MAGIC = "ARROWJ15".getBytes(StandardCharsets.US_ASCII);

    private ArrowIpc() {
    }

    /** 序列化（视图按可见窗口编码） */
    public static byte[] serialize(RecordBatch batch) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(MAGIC);
        putInt(out, batch.schema().fields().size());
        for (ArrowSchema.Field f : batch.schema().fields()) {
            byte[] name = f.name().getBytes(StandardCharsets.UTF_8);
            putInt(out, name.length);
            out.writeBytes(name);
            out.write((byte) f.type().ordinal());
            out.write((byte) (f.nullable() ? 1 : 0));
        }
        putInt(out, batch.rows());
        for (ArrowSchema.Field f : batch.schema().fields()) {
            if (f.type() == ArrowSchema.Type.UTF8) {
                writeVarLen(out, batch.varLenColumn(f.name()));
            } else {
                writeFixed(out, batch.fixedColumn(f.name()));
            }
        }
        CRC32 crc = new CRC32();
        crc.update(out.toByteArray());
        putLong(out, crc.getValue());
        return out.toByteArray();
    }

    private static void writeFixed(ByteArrayOutputStream out, ColumnVector col) {
        byte[] bits = col.validity().toPackedBits();
        putInt(out, bits.length);
        out.writeBytes(bits);
        for (int i = 0; i < col.length(); i++) {
            if (col.isNull(i)) {
                putLong(out, 0L);
                continue;
            }
            switch (col.type()) {
                case INT64 -> putLong(out, col.getLong(i));
                case FP64 -> putLong(out, Double.doubleToLongBits(col.getDouble(i)));
                default -> putLong(out, (Boolean) col.get(i) ? 1L : 0L);
            }
        }
    }

    private static void writeVarLen(ByteArrayOutputStream out, VarLenVector col) {
        byte[] bits = col.validity().toPackedBits();
        putInt(out, bits.length);
        out.writeBytes(bits);
        for (int i = 0; i < col.length(); i++) {
            if (col.isNull(i)) {
                putInt(out, -1);
                continue;
            }
            byte[] v = col.get(i).getBytes(StandardCharsets.UTF_8);
            putInt(out, v.length);
            out.writeBytes(v);
        }
    }

    /** 反序列化：魔数/长度/CRC 校验，损坏拒绝 */
    public static RecordBatch deserialize(byte[] bytes) {
        if (bytes.length < MAGIC.length + 8 || bytes[0] != MAGIC[0]) {
            throw new IllegalArgumentException("IPC 损坏: 魔数不符");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (bytes[i] != MAGIC[i]) {
                throw new IllegalArgumentException("IPC 损坏: 魔数不符");
            }
        }
        long crc = ByteBuffer.wrap(bytes, bytes.length - 8, 8).getLong();
        CRC32 check = new CRC32();
        check.update(bytes, 0, bytes.length - 8);
        if (crc != check.getValue()) {
            throw new IllegalArgumentException("IPC 损坏: CRC 不符");
        }
        try {
            ByteBuffer buf = ByteBuffer.wrap(bytes, MAGIC.length, bytes.length - MAGIC.length - 8);
            return readBatch(buf);
        } catch (BufferUnderflowException | IllegalArgumentException e) {
            throw new IllegalArgumentException("IPC 损坏: " + e.getMessage());
        }
    }

    private static RecordBatch readBatch(ByteBuffer buf) {
        int fieldCount = buf.getInt();
        java.util.List<ArrowSchema.Field> fields = new java.util.ArrayList<>();
        for (int i = 0; i < fieldCount; i++) {
            byte[] name = new byte[buf.getInt()];
            buf.get(name);
            ArrowSchema.Type type = ArrowSchema.Type.values()[buf.get()];
            boolean nullable = buf.get() == 1;
            fields.add(new ArrowSchema.Field(new String(name, StandardCharsets.UTF_8), type, nullable));
        }
        ArrowSchema schema = new ArrowSchema(fields);
        int rows = buf.getInt();
        RecordBatch.Builder builder = RecordBatch.builder(schema);
        java.util.List<java.util.List<Object>> table = new java.util.ArrayList<>();
        for (ArrowSchema.Field f : fields) {
            byte[] bits = new byte[buf.getInt()];
            buf.get(bits);
            ValidityBitmap validity = ValidityBitmap.fromPackedBits(bits, rows);
            java.util.List<Object> values = new java.util.ArrayList<>();
            if (f.type() == ArrowSchema.Type.UTF8) {
                for (int r = 0; r < rows; r++) {
                    int len = buf.getInt();
                    if (len < 0) {
                        values.add(null);
                    } else {
                        byte[] v = new byte[len];
                        buf.get(v);
                        values.add(new String(v, StandardCharsets.UTF_8));
                    }
                }
            } else {
                for (int r = 0; r < rows; r++) {
                    long raw = buf.getLong();
                    if (!validity.isValid(r)) {
                        values.add(null);
                    } else {
                        values.add(switch (f.type()) {
                            case INT64 -> raw;
                            case FP64 -> Double.longBitsToDouble(raw);
                            default -> raw == 1L;
                        });
                    }
                }
            }
            table.add(values);
        }
        for (int r = 0; r < rows; r++) {
            java.util.List<Object> row = new java.util.ArrayList<>();
            for (java.util.List<Object> col : table) {
                row.add(col.get(r));
            }
            builder.appendRow(row);
        }
        return builder.build();
    }

    private static void putInt(ByteArrayOutputStream out, int v) {
        out.write(v >>> 24);
        out.write(v >>> 16);
        out.write(v >>> 8);
        out.write(v);
    }

    private static void putLong(ByteArrayOutputStream out, long v) {
        for (int i = 7; i >= 0; i--) {
            out.write((int) (v >>> (i * 8)));
        }
    }
}
