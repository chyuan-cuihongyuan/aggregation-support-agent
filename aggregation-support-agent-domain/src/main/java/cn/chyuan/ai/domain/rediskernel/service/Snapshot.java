package cn.chyuan.ai.domain.rediskernel.service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

/**
 * RDB 式快照（工单 0653 BY7，redis 思想）。
 * dump 字节格式：魔数 CRDB+版本+键值条目（STR/ZSET 两型）+整包 CRC32 校验/
 * 损坏与截断拒绝/快照统计（键数/字节/耗时）登记访问器。
 */
public final class Snapshot {

    public static final String MAGIC = "CRDB";
    public static final int VERSION = 1;

    /** 值类型：字符串 / 有序集合 */
    public sealed interface Value permits Str, Zset {
    }

    public record Str(byte[] data) implements Value {
    }

    public record MemberScore(String member, double score) {
    }

    public record Zset(List<MemberScore> items) implements Value {
    }

    public record Entry(String key, Value value) {
    }

    public record Stat(String scene, int keys, long bytes, long costMs, long at) {
    }

    private Snapshot() {
    }

    public static byte[] dump(List<Entry> entries) {
        if (entries == null) {
            throw new IllegalArgumentException("快照条目不得为 null");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        try {
            out.write(MAGIC.getBytes(StandardCharsets.US_ASCII));
            out.writeByte(VERSION);
            out.writeInt(entries.size());
            for (Entry entry : entries) {
                byte[] key = entry.key().getBytes(StandardCharsets.UTF_8);
                out.writeInt(key.length);
                out.write(key);
                if (entry.value() instanceof Str str) {
                    out.writeByte(0);
                    out.writeInt(str.data().length);
                    out.write(str.data());
                } else if (entry.value() instanceof Zset zset) {
                    out.writeByte(1);
                    out.writeInt(zset.items().size());
                    for (MemberScore item : zset.items()) {
                        byte[] member = item.member().getBytes(StandardCharsets.UTF_8);
                        out.writeInt(member.length);
                        out.write(member);
                        out.writeLong(Double.doubleToLongBits(item.score()));
                    }
                } else {
                    throw new IllegalArgumentException("未知快照值类型");
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("快照序列化失败", e);
        }
        byte[] payload = bos.toByteArray();
        byte[] full = Arrays.copyOf(payload, payload.length + 8);
        long crc = crc32(payload);
        for (int i = 0; i < 8; i++) {
            full[full.length - 8 + i] = (byte) ((crc >>> (56 - 8 * i)) & 0xFFL);
        }
        return full;
    }

    public static List<Entry> load(byte[] snapshot) {
        if (snapshot == null || snapshot.length < 4 + 1 + 8) {
            throw new IllegalArgumentException("快照截断：长度不足");
        }
        int payloadLen = snapshot.length - 8;
        byte[] payload = Arrays.copyOfRange(snapshot, 0, payloadLen);
        long trailer = 0;
        for (int i = 0; i < 8; i++) {
            trailer = (trailer << 8) | (snapshot[payloadLen + i] & 0xFFL);
        }
        if (crc32(payload) != trailer) {
            throw new IllegalArgumentException("快照校验失败：CRC32 不匹配");
        }
        if (!MAGIC.equals(new String(payload, 0, 4, StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("快照魔数不匹配");
        }
        if (payload[4] != VERSION) {
            throw new IllegalArgumentException("快照版本不兼容：" + payload[4]);
        }
        List<Entry> entries = new ArrayList<>();
        Cursor cursor = new Cursor(payload, 5);
        int count = cursor.readInt();
        for (int i = 0; i < count; i++) {
            String key = cursor.readString();
            int type = cursor.readByte();
            if (type == 0) {
                entries.add(new Entry(key, new Str(cursor.readBytes())));
            } else if (type == 1) {
                int items = cursor.readInt();
                List<MemberScore> members = new ArrayList<>(items);
                for (int j = 0; j < items; j++) {
                    String member = cursor.readString();
                    members.add(new MemberScore(member, Double.longBitsToDouble(cursor.readLong())));
                }
                entries.add(new Entry(key, new Zset(members)));
            } else {
                throw new IllegalArgumentException("快照损坏：未知值类型 " + type);
            }
        }
        return entries;
    }

    private static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    private static final class Cursor {
        private final byte[] data;
        private int pos;

        Cursor(byte[] data, int pos) {
            this.data = data;
            this.pos = pos;
        }

        int readByte() {
            require(1);
            return data[pos++] & 0xFF;
        }

        int readInt() {
            require(4);
            int v = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16)
                    | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            pos += 4;
            return v;
        }

        long readLong() {
            require(8);
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (data[pos + i] & 0xFFL);
            }
            pos += 8;
            return v;
        }

        byte[] readBytes() {
            int len = readInt();
            if (len < 0) {
                throw new IllegalArgumentException("快照损坏：负长度字段");
            }
            require(len);
            byte[] out = Arrays.copyOfRange(data, pos, pos + len);
            pos += len;
            return out;
        }

        String readString() {
            return new String(readBytes(), StandardCharsets.UTF_8);
        }

        private void require(int n) {
            if (pos + n > data.length) {
                throw new IllegalArgumentException("快照截断：字段越界");
            }
        }
    }

    /** 快照统计登记（场景/键数/字节/耗时/样本时间） */
    public static final class Stats {
        private final List<Stat> stats = new ArrayList<>();

        public void record(String scene, int keys, long bytes, long costMs, long at) {
            if (scene == null || scene.isBlank()) {
                throw new IllegalArgumentException("快照场景不得为空");
            }
            stats.add(new Stat(scene, keys, bytes, costMs, at));
        }

        public List<Stat> all() {
            return new ArrayList<>(stats);
        }
    }
}
