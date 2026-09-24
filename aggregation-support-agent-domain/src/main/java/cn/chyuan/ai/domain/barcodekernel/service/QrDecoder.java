package cn.chyuan.ai.domain.barcodekernel.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 解码器（工单 0722 CG7，zxing 思想）。
 * 读取格式信息还原纠错级与掩码/去掩码/zigzag 收集码字/
 * RS 纠错还原数据段/版本不匹配与不可纠拒绝。
 */
public final class QrDecoder {

    private QrDecoder() {
    }

    public record Decoded(String text, int version, Segmentizer.EcLevel level, int mask, int corrected) {
    }

    /** 解码：模块矩阵 → 文本 */
    public static Decoded decode(boolean[][] modules) {
        int size = modules.length;
        if ((size - 17) % 4 != 0) {
            throw new IllegalArgumentException("非法矩阵尺寸: " + size);
        }
        int version = (size - 17) / 4;
        if (version < 1 || version > 4) {
            throw new IllegalArgumentException("版本超子集（1-4）: " + version);
        }
        // 格式信息（左上第一份）：还原纠错级与掩码
        int[][] first = {{8, 0}, {8, 1}, {8, 2}, {8, 3}, {8, 4}, {8, 5}, {8, 7}, {8, 8},
                {7, 8}, {5, 8}, {4, 8}, {3, 8}, {2, 8}, {1, 8}, {0, 8}};
        int format = 0;
        for (int[] pos : first) {
            format = (format << 1) | (modules[pos[0]][pos[1]] ? 1 : 0);
        }
        int unmasked = format ^ 0x5412;
        if (bch15Check(unmasked)) {
            throw new IllegalArgumentException("格式信息 BCH 校验失败");
        }
        int ecBits = (unmasked >> 13) & 0b11;
        int mask = (unmasked >> 10) & 0b111;
        Segmentizer.EcLevel level = Segmentizer.EcLevel.values()[ecBits];
        if (mask > 7) {
            throw new IllegalArgumentException("非法掩码: " + mask);
        }

        // 保留图重建 + 去掩码 + 收集码字
        QrMatrix scaffold = QrMatrix.scaffold(version);
        int[] rawBits = scaffold.collectData();
        int totalBits = Math.min(rawBits.length, 8 * (Segmentizer.blockSpec(version, level).dataTotal()
                + Segmentizer.blockSpec(version, level).blocks() * Segmentizer.blockSpec(version, level).ecPerBlock()));
        int[] codewords = new int[totalBits / 8];
        int bitAt = 0;
        boolean up = true;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) {
                right = 5;
            }
            for (int vert = 0; vert < size; vert++) {
                int row = up ? size - 1 - vert : vert;
                for (int col = right; col >= right - 1; col--) {
                    if (scaffold.isReserved(row, col)) {
                        continue;
                    }
                    boolean bit = modules[row][col];
                    if (Masking.isMasked(mask, row, col)) {
                        bit = !bit;
                    }
                    codewords[bitAt / 8] = (codewords[bitAt / 8] << 1) | (bit ? 1 : 0);
                    bitAt++;
                    if (bitAt >= totalBits) {
                        break;
                    }
                }
                if (bitAt >= totalBits) {
                    break;
                }
            }
            if (bitAt >= totalBits) {
                break;
            }
            up = !up;
        }

        // 去交织 + RS 纠错 + 还原数据码字
        ReedSolomon.BlockSpec spec = Segmentizer.blockSpec(version, level);
        List<int[]> blocks = ReedSolomon.deinterleave(codewords, spec);
        List<Integer> dataBytes = new ArrayList<>();
        int corrected = 0;
        for (int[] block : blocks) {
            int[] fixed = ReedSolomon.correct(block, spec.ecPerBlock());
            for (int i = 0; i < block.length; i++) {
                if (fixed[i] != block[i]) {
                    corrected++;
                }
            }
            for (int i = 0; i < spec.dataPerBlock(); i++) {
                dataBytes.add(fixed[i]);
            }
        }

        return new Decoded(parseSegments(dataBytes), version, level, mask, corrected);
    }

    /** 数据段解析：模式指示 + 计数 + 载荷 */
    static String parseSegments(List<Integer> dataBytes) {
        StringBuilder bits = new StringBuilder();
        for (int b : dataBytes) {
            for (int i = 7; i >= 0; i--) {
                bits.append((b >> i) & 1);
            }
        }
        int at = 0;
        StringBuilder text = new StringBuilder();
        boolean sawAny = false;
        while (at + 4 <= bits.length()) {
            int mode = Integer.parseInt(bits.substring(at, at + 4), 2);
            at += 4;
            if (mode == 0) {
                break;
            }
            sawAny = true;
            if (mode == 0b0001) {
                int count = readInt(bits, at, 10);
                at += 10;
                while (count >= 3 && at + 10 <= bits.length()) {
                    text.append(String.format("%03d", readInt(bits, at, 10)));
                    at += 10;
                    count -= 3;
                }
                if (count == 2 && at + 7 <= bits.length()) {
                    text.append(String.format("%02d", readInt(bits, at, 7)));
                    at += 7;
                } else if (count == 1 && at + 4 <= bits.length()) {
                    text.append(readInt(bits, at, 4));
                    at += 4;
                }
            } else if (mode == 0b0010) {
                int count = readInt(bits, at, 9);
                at += 9;
                while (count >= 2 && at + 11 <= bits.length()) {
                    int pair = readInt(bits, at, 11);
                    at += 11;
                    text.append(Segmentizer.ALPHA_CHARSET.charAt(pair / 45))
                            .append(Segmentizer.ALPHA_CHARSET.charAt(pair % 45));
                    count -= 2;
                }
                if (count == 1 && at + 6 <= bits.length()) {
                    text.append(Segmentizer.ALPHA_CHARSET.charAt(readInt(bits, at, 6)));
                    at += 6;
                }
            } else if (mode == 0b0100) {
                int count = readInt(bits, at, 8);
                at += 8;
                byte[] bytes = new byte[count];
                for (int i = 0; i < count; i++) {
                    bytes[i] = (byte) readInt(bits, at, 8);
                    at += 8;
                }
                text.append(new String(bytes, StandardCharsets.UTF_8));
            } else {
                throw new IllegalStateException("未知模式指示: " + mode);
            }
        }
        if (!sawAny) {
            throw new IllegalStateException("无数据段");
        }
        return text.toString();
    }

    private static int readInt(StringBuilder bits, int at, int width) {
        if (at + width > bits.length()) {
            throw new IllegalStateException("位流截断");
        }
        return Integer.parseInt(bits.substring(at, at + width), 2);
    }

    /** BCH(15,5) 校验：除 0x537 余 0（XOR 掩码后） */
    private static boolean bch15Check(int value15) {
        int value = value15;
        for (int i = 14; i >= 10; i--) {
            if (((value >> i) & 1) == 1) {
                value ^= 0x537 << (i - 10);
            }
        }
        return value != 0;
    }
}
