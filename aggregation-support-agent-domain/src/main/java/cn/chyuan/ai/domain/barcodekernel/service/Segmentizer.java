package cn.chyuan.ai.domain.barcodekernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 模式与容量（工单 0716 CG1，zxing 思想）。
 * 数字 3 合 10bit/字母 2 合 11bit/字节 8bit 模式分段与切换/
 * 版本 1-4 × 4 纠错级容量与分块结构表/最短版本选择/空输入与超容拒绝。
 */
public final class Segmentizer {

    /** 纠错级别 */
    public enum EcLevel {L, M, Q, H}

    /** 分段模式：数字/字母/字节 */
    public enum Mode {DIGIT, ALPHA, BYTE}

    public record Segment(Mode mode, String text) {
    }

    public record Bitstream(List<Integer> bits, int version, int dataCodewords, List<Segment> segments) {
    }

    public static final String ALPHA_CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";

    private Segmentizer() {
    }

    /** 字符分类 */
    public static Mode classOf(char c) {
        if (c >= '0' && c <= '9') {
            return Mode.DIGIT;
        }
        if (ALPHA_CHARSET.indexOf(c) >= 0) {
            return Mode.ALPHA;
        }
        return Mode.BYTE;
    }

    /** 贪心分段：连续同类字符成段（数字段优先合并） */
    public static List<Segment> segmentize(String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("空输入");
        }
        List<Segment> segments = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            Mode mode = classOf(text.charAt(i));
            int j = i + 1;
            while (j < text.length() && classOf(text.charAt(j)) == mode) {
                j++;
            }
            segments.add(new Segment(mode, text.substring(i, j)));
            i = j;
        }
        return segments;
    }

    /** 分段位流：段模式指示 4bit + 计数字段（v1-4：数字 10/字母 9/字节 8）+ 数据位 */
    public static int bitCount(List<Segment> segments, int version) {
        int bits = 0;
        for (Segment segment : segments) {
            bits += 4 + countBits(segment.mode(), version) + dataBits(segment);
        }
        return bits;
    }

    private static int countBits(Mode mode, int version) {
        return switch (mode) {
            case DIGIT -> 10;
            case ALPHA -> 9;
            case BYTE -> version >= 10 ? 16 : 8;
        };
    }

    /** 字节模式按 UTF-8 字节数计（与计数域一致） */
    private static int byteLen(Segment segment) {
        return segment.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    private static int dataBits(Segment segment) {
        int len = segment.mode() == Mode.BYTE ? byteLen(segment) : segment.text().length();
        return switch (segment.mode()) {
            case DIGIT -> (len / 3) * 10 + switch (len % 3) {
                case 1 -> 4;
                case 2 -> 7;
                default -> 0;
            };
            case ALPHA -> (len / 2) * 11 + (len % 2 == 1 ? 6 : 0);
            case BYTE -> len * 8;
        };
    }

    /** 数据位编码 */
    public static List<Integer> encodeBits(List<Segment> segments) {
        List<Integer> bits = new ArrayList<>();
        for (Segment segment : segments) {
            appendInt(bits, segment.mode() == Mode.DIGIT ? 0b0001
                    : segment.mode() == Mode.ALPHA ? 0b0010 : 0b0100, 4);
            appendInt(bits, segment.mode() == Mode.BYTE ? byteLen(segment) : segment.text().length(),
                    countBits(segment.mode(), 1));
            String s = segment.text();
            if (segment.mode() == Mode.DIGIT) {
                int i = 0;
                for (; i + 3 <= s.length(); i += 3) {
                    appendInt(bits, Integer.parseInt(s.substring(i, i + 3)), 10);
                }
                if (s.length() - i == 2) {
                    appendInt(bits, Integer.parseInt(s.substring(i)), 7);
                } else if (s.length() - i == 1) {
                    appendInt(bits, s.charAt(i) - '0', 4);
                }
            } else if (segment.mode() == Mode.ALPHA) {
                int i = 0;
                for (; i + 2 <= s.length(); i += 2) {
                    appendInt(bits, alphaValue(s.charAt(i)) * 45 + alphaValue(s.charAt(i + 1)), 11);
                }
                if (i < s.length()) {
                    appendInt(bits, alphaValue(s.charAt(i)), 6);
                }
            } else {
                for (byte b : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                    appendInt(bits, b & 0xff, 8);
                }
            }
        }
        return bits;
    }

    private static int alphaValue(char c) {
        int at = ALPHA_CHARSET.indexOf(c);
        if (at < 0) {
            throw new IllegalArgumentException("非字母模式字符: " + c);
        }
        return at;
    }

    private static void appendInt(List<Integer> bits, int value, int width) {
        for (int i = width - 1; i >= 0; i--) {
            bits.add((value >> i) & 1);
        }
    }

    /** 版本容量与分块结构表（v1-4 × L/M/Q/H）：数据码字/块数/每块纠错码字 */
    public static ReedSolomon.BlockSpec blockSpec(int version, EcLevel level) {
        return switch (version) {
            case 1 -> switch (level) {
                case L -> new ReedSolomon.BlockSpec(1, 19, 7);
                case M -> new ReedSolomon.BlockSpec(1, 16, 10);
                case Q -> new ReedSolomon.BlockSpec(1, 13, 13);
                case H -> new ReedSolomon.BlockSpec(1, 9, 17);
            };
            case 2 -> switch (level) {
                case L -> new ReedSolomon.BlockSpec(1, 34, 10);
                case M -> new ReedSolomon.BlockSpec(1, 28, 16);
                case Q -> new ReedSolomon.BlockSpec(1, 22, 22);
                case H -> new ReedSolomon.BlockSpec(1, 16, 28);
            };
            case 3 -> switch (level) {
                case L -> new ReedSolomon.BlockSpec(1, 55, 15);
                case M -> new ReedSolomon.BlockSpec(1, 44, 26);
                case Q -> new ReedSolomon.BlockSpec(2, 17, 18);
                case H -> new ReedSolomon.BlockSpec(2, 13, 22);
            };
            case 4 -> switch (level) {
                case L -> new ReedSolomon.BlockSpec(1, 80, 20);
                case M -> new ReedSolomon.BlockSpec(2, 32, 18);
                case Q -> new ReedSolomon.BlockSpec(2, 24, 26);
                case H -> new ReedSolomon.BlockSpec(4, 9, 16);
            };
            default -> throw new IllegalArgumentException("版本超子集（1-4）: " + version);
        };
    }

    /** 最短版本选择：位流 + 终止符补齐后按容量选最小版本 */
    public static Bitstream fit(String text, EcLevel level) {
        List<Segment> segments = segmentize(text);
        for (int version = 1; version <= 4; version++) {
            int bits = bitCount(segments, version);
            int capacity = blockSpec(version, level).dataTotal() * 8;
            if (bits + 4 <= capacity) {
                List<Integer> full = new ArrayList<>(encodeBits(segments));
                int terminator = Math.min(4, capacity - full.size());
                for (int i = 0; i < terminator; i++) {
                    full.add(0);
                }
                while (full.size() % 8 != 0) {
                    full.add(0);
                }
                int padFlip = 0;
                while (full.size() < capacity) {
                    full.addAll(bits0(padFlip % 2 == 0 ? 0xec : 0x11));
                    padFlip++;
                }
                return new Bitstream(full, version, capacity / 8, segments);
            }
        }
        throw new IllegalArgumentException("超容：需版本 5+（子集 1-4）");
    }

    /** 位流 → 码字 */
    public static int[] toCodewords(List<Integer> bits) {
        if (bits.size() % 8 != 0) {
            throw new IllegalArgumentException("位流未按字节对齐");
        }
        int[] out = new int[bits.size() / 8];
        for (int i = 0; i < out.length; i++) {
            int b = 0;
            for (int j = 0; j < 8; j++) {
                b = (b << 1) | bits.get(i * 8 + j);
            }
            out[i] = b;
        }
        return out;
    }

    private static List<Integer> bits0(int value) {
        List<Integer> bits = new ArrayList<>(8);
        for (int i = 7; i >= 0; i--) {
            bits.add((value >> i) & 1);
        }
        return bits;
    }
}
