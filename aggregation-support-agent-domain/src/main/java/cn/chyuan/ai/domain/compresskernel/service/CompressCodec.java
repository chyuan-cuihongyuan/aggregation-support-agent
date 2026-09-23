package cn.chyuan.ai.domain.compresskernel.service;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

/**
 * 压缩编解码器（工单 0588 BR3 + 0590 BR5，zstd 块帧思想）。
 * 帧头（魔数/版本/级别/词典指纹）/块切分（码长表+记号位流）/
 * CRC32 逐块与整帧校验/LZ 指针逆向拷贝（重叠语义：长度>距离循环复制）/
 * 越界指针与损坏帧拒绝/压缩解压全量往返字节相等。
 */
public final class CompressCodec {

    /** 帧魔数 CKZ1 */
    public static final byte[] MAGIC = {0x43, 0x4B, 0x5A, 0x31};
    /** 帧版本 */
    public static final int VERSION = 1;
    /** 每块原始字节数粒度（Match 可跨块，块产出以记号实际覆盖为准） */
    public static final int BLOCK_SIZE = 64 * 1024;
    /** 长度字段位宽（length - minMatch 最大 255） */
    private static final int LENGTH_BITS = 8;

    /** 压缩结果：帧字节 + 统计 */
    public record Compressed(byte[] frame, int level, long rawBytes, long compressedBytes) {

        public double ratio() {
            return rawBytes == 0 ? 0d : (double) compressedBytes / rawBytes;
        }
    }

    private CompressCodec() {
    }

    /** 压缩：LZ77 匹配 + 前缀编码成帧（dict 为可选预设词典，null/空等价无词典） */
    public static Compressed compress(byte[] input, byte[] dict, int level) {
        if (input == null) {
            throw new IllegalArgumentException("输入不得为 null");
        }
        CompressionLevels.Params params = CompressionLevels.of(level);
        byte[] dictionary = dict == null ? new byte[0] : dict;
        CompressionDictionary.validate(dictionary);
        if (dictionary.length > params.windowSize()) {
            throw new IllegalArgumentException("词典长度超过窗口大小：词典将不可达");
        }
        List<Lz77Matcher.Token> tokens =
                Lz77Matcher.match(input, dictionary, params.windowSize(), params.minMatch(),
                        params.maxChain(), params.lazy());
        long dictFp = CompressionDictionary.fingerprint(dictionary);
        CRC32 rawCrc = new CRC32();
        rawCrc.update(input);

        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length / 2 + 64);
        out.writeBytes(MAGIC);
        out.write(VERSION);
        out.write(level);
        writeLong(out, dictFp);
        int blockCount = Math.max(1, (input.length + BLOCK_SIZE - 1) / BLOCK_SIZE);
        writeInt(out, blockCount);
        int tokenIndex = 0;
        int rawPos = 0;
        for (int b = 0; b < blockCount; b++) {
            int blockStart = rawPos;
            int blockLimit = Math.min(input.length, blockStart + BLOCK_SIZE);
            List<Lz77Matcher.Token> blockTokens = new ArrayList<>();
            while (rawPos < blockLimit && tokenIndex < tokens.size()) {
                Lz77Matcher.Token token = tokens.get(tokenIndex);
                blockTokens.add(token);
                rawPos += outputSize(token);
                tokenIndex++;
            }
            int rawLen = rawPos - blockStart;
            long[] freqs = new long[PrefixCode.SYMBOLS];
            freqs[PrefixCode.END_OF_BLOCK] = 1;
            for (Lz77Matcher.Token token : blockTokens) {
                if (token instanceof Lz77Matcher.Literal lit) {
                    freqs[lit.value()]++;
                }
            }
            int[] lengths = PrefixCode.buildLengths(freqs);
            PrefixCode.CanonicalCode code = PrefixCode.CanonicalCode.of(lengths);
            BitWriter bits = new BitWriter();
            bits.writeByte(params.offsetBits());
            bits.writeByte(params.minMatch());
            for (int len : lengths) {
                bits.writeByte(len);
            }
            for (Lz77Matcher.Token token : blockTokens) {
                if (token instanceof Lz77Matcher.Literal lit) {
                    bits.writeBit(0);
                    writeHuffman(bits, code, lit.value());
                } else if (token instanceof Lz77Matcher.Match match) {
                    bits.writeBit(1);
                    bits.writeBits(match.offset() - 1, params.offsetBits());
                    bits.writeBits(match.length() - params.minMatch(), LENGTH_BITS);
                }
            }
            bits.writeBit(0);
            writeHuffman(bits, code, PrefixCode.END_OF_BLOCK);
            byte[] payload = bits.toByteArray();
            CRC32 payloadCrc = new CRC32();
            payloadCrc.update(payload);
            writeInt(out, rawLen);
            writeInt(out, payload.length);
            writeLong(out, payloadCrc.getValue());
            out.writeBytes(payload);
        }
        writeLong(out, input.length);
        writeLong(out, rawCrc.getValue());
        byte[] frame = out.toByteArray();
        return new Compressed(frame, level, input.length, frame.length);
    }

    /** 解压：校验魔数/版本/词典指纹/CRC，逆向 LZ 指针（重叠拷贝），返回原始字节 */
    public static byte[] decompress(byte[] frame, byte[] dict) {
        if (frame == null || frame.length < 50) {
            throw new IllegalArgumentException("帧过短或为 null");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (frame[i] != MAGIC[i]) {
                throw new IllegalArgumentException("帧魔数不符：损坏帧拒绝");
            }
        }
        if (Byte.toUnsignedInt(frame[4]) != VERSION) {
            throw new IllegalArgumentException("帧版本不符：" + Byte.toUnsignedInt(frame[4]));
        }
        int level = Byte.toUnsignedInt(frame[5]);
        CompressionLevels.Params params = CompressionLevels.of(level);
        byte[] dictionary = dict == null ? new byte[0] : dict;
        long dictFp = readLong(frame, 6);
        if (dictFp != CompressionDictionary.fingerprint(dictionary)) {
            throw new IllegalArgumentException("词典指纹不符：须用同一词典解压");
        }
        if (dictionary.length > params.windowSize()) {
            throw new IllegalArgumentException("词典长度超过窗口大小");
        }
        long rawTotal = readLong(frame, frame.length - 16);
        long rawCrcValue = readLong(frame, frame.length - 8);
        if (rawTotal < 0 || rawTotal > 512L * 1024 * 1024) {
            throw new IllegalArgumentException("声明总长非法");
        }
        int pos = 14;
        int blockCount = readInt(frame, pos);
        pos += 4;
        if (blockCount <= 0) {
            throw new IllegalArgumentException("块数非法：" + blockCount);
        }
        int dictLen = dictionary.length;
        byte[] history = new byte[dictLen + (int) rawTotal];
        System.arraycopy(dictionary, 0, history, 0, dictLen);
        int globalProduced = 0;
        for (int b = 0; b < blockCount; b++) {
            int rawLen = readInt(frame, pos);
            int payloadLen = readInt(frame, pos + 4);
            long blockCrc = readLong(frame, pos + 8);
            pos += 16;
            if (rawLen < 0 || payloadLen < 0 || pos + payloadLen > frame.length - 16) {
                throw new IllegalArgumentException("块长度非法：损坏帧拒绝");
            }
            byte[] payload = Arrays.copyOfRange(frame, pos, pos + payloadLen);
            pos += payloadLen;
            CRC32 payloadCheck = new CRC32();
            payloadCheck.update(payload);
            if (payloadCheck.getValue() != blockCrc) {
                throw new IllegalArgumentException("块载荷 CRC32 不符：损坏帧拒绝");
            }
            BitReader bits = new BitReader(payload);
            int offsetBits = bits.readByte();
            int minMatch = bits.readByte();
            if (offsetBits != params.offsetBits() || minMatch != params.minMatch()
                    || minMatch < Lz77Matcher.HASH_LENGTH) {
                throw new IllegalArgumentException("块参数与级别档位不符");
            }
            int[] lengths = new int[PrefixCode.SYMBOLS];
            for (int s = 0; s < PrefixCode.SYMBOLS; s++) {
                lengths[s] = bits.readByte();
            }
            PrefixCode.CanonicalDecoder decoder = new PrefixCode.CanonicalDecoder(lengths);
            int produced = 0;
            boolean ended = false;
            while (!ended) {
                int flag = bits.readBit();
                if (flag == 0) {
                    int symbol = decoder.decode(bits);
                    if (symbol == PrefixCode.END_OF_BLOCK) {
                        ended = true;
                    } else {
                        if (produced >= rawLen) {
                            throw new IllegalArgumentException("块输出超出声明长度");
                        }
                        history[dictLen + globalProduced + produced] = (byte) symbol;
                        produced++;
                    }
                } else {
                    int offset = (int) bits.readBits(offsetBits) + 1;
                    int length = (int) bits.readBits(LENGTH_BITS) + minMatch;
                    int available = dictLen + globalProduced + produced;
                    if (offset > available) {
                        throw new IllegalArgumentException("越界指针拒绝：offset " + offset + " > 已产出 " + available);
                    }
                    if (produced + length > rawLen) {
                        throw new IllegalArgumentException("块输出超出声明长度");
                    }
                    int from = available - offset;
                    for (int k = 0; k < length; k++) {
                        history[dictLen + globalProduced + produced] = history[from];
                        produced++;
                        from++;
                    }
                }
            }
            if (produced != rawLen) {
                throw new IllegalArgumentException("块产出与声明长度不符：" + produced + " != " + rawLen);
            }
            globalProduced += rawLen;
        }
        if (globalProduced != rawTotal) {
            throw new IllegalArgumentException("总长度不符：" + globalProduced + " != " + rawTotal);
        }
        CRC32 whole = new CRC32();
        whole.update(history, dictLen, (int) rawTotal);
        if (whole.getValue() != rawCrcValue) {
            throw new IllegalArgumentException("整帧 CRC32 不符：损坏帧拒绝");
        }
        return Arrays.copyOfRange(history, dictLen, dictLen + (int) rawTotal);
    }

    private static int outputSize(Lz77Matcher.Token token) {
        if (token instanceof Lz77Matcher.Literal) {
            return 1;
        }
        return ((Lz77Matcher.Match) token).length();
    }

    private static void writeHuffman(BitWriter bits, PrefixCode.CanonicalCode code, int symbol) {
        int value = code.codeOf(symbol);
        int len = code.lengths()[symbol];
        for (int i = len - 1; i >= 0; i--) {
            bits.writeBit((value >> i) & 1);
        }
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeLong(ByteArrayOutputStream out, long value) {
        for (int i = 7; i >= 0; i--) {
            out.write((int) ((value >>> (8 * i)) & 0xFF));
        }
    }

    private static int readInt(byte[] buf, int pos) {
        return ((buf[pos] & 0xFF) << 24) | ((buf[pos + 1] & 0xFF) << 16)
                | ((buf[pos + 2] & 0xFF) << 8) | (buf[pos + 3] & 0xFF);
    }

    private static long readLong(byte[] buf, int pos) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (buf[pos + i] & 0xFFL);
        }
        return v;
    }

    /** 位写入器：MSB 先行，按字节聚合 */
    static final class BitWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int current;
        private int filled;

        void writeBit(int bit) {
            current = (current << 1) | (bit & 1);
            filled++;
            if (filled == 8) {
                out.write(current);
                current = 0;
                filled = 0;
            }
        }

        void writeBits(long value, int count) {
            for (int i = count - 1; i >= 0; i--) {
                writeBit((int) ((value >>> i) & 1));
            }
        }

        void writeByte(int value) {
            writeBits(value & 0xFFL, 8);
        }

        void flush() {
            if (filled > 0) {
                current <<= (8 - filled);
                out.write(current);
                current = 0;
                filled = 0;
            }
        }

        byte[] toByteArray() {
            flush();
            return out.toByteArray();
        }
    }

    /** 位读取器：与 BitWriter 对偶；越界读取拒绝 */
    static final class BitReader implements PrefixCode.CanonicalDecoder.BitReader {
        private final byte[] buf;
        private int pos;
        private int bitPos;

        BitReader(byte[] buf) {
            this.buf = buf;
        }

        @Override
        public int readBit() {
            if (pos >= buf.length) {
                throw new IllegalArgumentException("位流耗尽：损坏帧拒绝");
            }
            int bit = (buf[pos] >> (7 - bitPos)) & 1;
            bitPos++;
            if (bitPos == 8) {
                pos++;
                bitPos = 0;
            }
            return bit;
        }

        long readBits(int count) {
            long value = 0;
            for (int i = 0; i < count; i++) {
                value = (value << 1) | readBit();
            }
            return value;
        }

        int readByte() {
            return (int) readBits(8);
        }
    }
}
