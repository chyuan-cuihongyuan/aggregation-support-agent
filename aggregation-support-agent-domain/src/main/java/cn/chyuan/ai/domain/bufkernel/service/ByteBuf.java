package cn.chyuan.ai.domain.bufkernel.service;

/**
 * 池化字节缓冲（工单 1196 FE1 / 1199 FE4 / 1200 FE5，netty ByteBuf 思想）。
 * 引用计数（创建即 1，retain 增 release 减，归零回收）；切片共享底层独立读写位；
 * readerIndex/writerIndex 读写指针。parent 哨兵：0=本体，&gt;0 共享切片父 id，&lt;0 retained 切片父 id。
 * 存活校验（含父联动）统一由 PoolArena 把关。
 */
final class ByteBuf {

    final int id;
    final byte[] memory;
    final int offset;
    final int capacity;
    final int parent;
    int readerIndex;
    int writerIndex;
    int refs = 1;
    boolean freed;

    ByteBuf(int id, byte[] memory, int offset, int capacity, int parent) {
        this.id = id;
        this.memory = memory;
        this.offset = offset;
        this.capacity = capacity;
        this.parent = parent;
    }

    public int id() {
        return id;
    }

    public int capacity() {
        return capacity;
    }

    int offset() {
        return offset;
    }

    public int refs() {
        return refs;
    }

    public boolean freed() {
        return freed;
    }

    /** 可读字节数 */
    public int readable() {
        return writerIndex - readerIndex;
    }

    /** 可写字节数 */
    public int writable() {
        return capacity - writerIndex;
    }

    public int readerIndex() {
        return readerIndex;
    }

    public int writerIndex() {
        return writerIndex;
    }

    void retain() {
        refs++;
    }

    /** 归还引用；归零返回 true 交由池回收 */
    boolean release() {
        refs--;
        if (refs == 0) {
            freed = true;
            return true;
        }
        return false;
    }

    /** 追加写入，writerIndex 前移 */
    void write(byte[] data) {
        System.arraycopy(data, 0, memory, offset + writerIndex, data.length);
        writerIndex += data.length;
    }

    /** 从 readerIndex 读 length 字节并前移 */
    byte[] read(int length) {
        byte[] out = new byte[length];
        System.arraycopy(memory, offset + readerIndex, out, 0, length);
        readerIndex += length;
        return out;
    }
}
