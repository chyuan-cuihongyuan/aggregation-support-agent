package cn.chyuan.ai.domain.barcodekernel.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 条码内核测试（工单 0716-0722 CG1-CG7，zxing 思想）。
 * GF(256) 四则/RS 编码纠错不可纠拒绝/分块表交织/模式容量选版/
 * 功能图案与 BCH/掩码罚分/端到端编解码与拒绝路径。
 */
class BarcodeKernelTest {

    // ---- CG2 GF(256) ----

    @Test
    void gf256TablesAndArithmetic() {
        assertEquals(1, Gf256.mul(1, 1));
        assertEquals(0, Gf256.mul(0, 200));
        for (int a = 1; a < 255; a++) {
            assertEquals(1, Gf256.mul(Gf256.exp(a), Gf256.inverse(Gf256.exp(a))), "α^" + a);
        }
        assertEquals(3 * 5L, Gf256.mul(3, 5) == 0 ? 0 : 15);
        int a = 0x53;
        int b = 0xca;
        assertEquals(b, Gf256.div(Gf256.mul(a, b), a));
        assertEquals(0x53 ^ 0xca, Gf256.add(0x53, 0xca));
        assertEquals(0, Gf256.add(0x53, 0x53), "GF 自加为零");
        assertNotEquals(0, Gf256.mul(3, 5));
        int[] p = {1, 1, 1};
        assertEquals(1, Gf256.polyEval(p, 0), "GF(2) 上 1+1+1=1");
        assertThrows(IllegalArgumentException.class, () -> Gf256.add(256, 1));
        assertThrows(ArithmeticException.class, () -> Gf256.div(1, 0));
        assertThrows(IllegalArgumentException.class, () -> Gf256.mul(-1, 1));
    }

    // ---- CG3 RS 编码与纠错 ----

    @Test
    void rsEncodeRemainderProperty() {
        int[] data = {0x40, 0xd2, 0x75, 0x47, 0x76, 0x17, 0x32, 0x06, 0x27, 0x26, 0x96, 0xc6, 0xc6, 0x96, 0x70, 0xec};
        int[] ec = ReedSolomon.encode(data, 10);
        assertEquals(10, ec.length);
        int[] block = new int[data.length + 10];
        System.arraycopy(data, 0, block, 0, data.length);
        System.arraycopy(ec, 0, block, data.length, 10);
        for (int s : ReedSolomon.syndromes(block, 10)) {
            assertEquals(0, s, "数据+纠错综合子全零");
        }
        assertThrows(IllegalArgumentException.class, () -> ReedSolomon.encode(new int[0], 5));
        assertThrows(IllegalArgumentException.class, () -> ReedSolomon.encode(data, 0));
    }

    @Test
    void rsBlockTableAndInterleave() {
        assertEquals(new ReedSolomon.BlockSpec(2, 17, 18), Segmentizer.blockSpec(3, Segmentizer.EcLevel.Q));
        assertEquals(new ReedSolomon.BlockSpec(4, 9, 16), Segmentizer.blockSpec(4, Segmentizer.EcLevel.H));
        assertEquals(80, Segmentizer.blockSpec(4, Segmentizer.EcLevel.L).dataTotal());
        assertThrows(IllegalArgumentException.class, () -> Segmentizer.blockSpec(5, Segmentizer.EcLevel.L));

        List<int[]> dataBlocks = List.of(new int[]{1, 2}, new int[]{3, 4});
        List<int[]> ecBlocks = List.of(new int[]{5, 6}, new int[]{7, 8});
        int[] inter = ReedSolomon.interleave(dataBlocks, ecBlocks);
        assertArrayEquals(new int[]{1, 3, 2, 4, 5, 7, 6, 8}, inter);
        List<int[]> back = ReedSolomon.deinterleave(inter, new ReedSolomon.BlockSpec(2, 2, 2));
        assertArrayEquals(new int[]{1, 2, 5, 6}, back.get(0));
        assertArrayEquals(new int[]{3, 4, 7, 8}, back.get(1));
        assertThrows(IllegalArgumentException.class, () -> ReedSolomon.deinterleave(new int[5],
                new ReedSolomon.BlockSpec(2, 2, 2)));
    }

    @Test
    void rsCorrectSingleAndDoubleError() {
        int[] data = new int[32];
        for (int i = 0; i < data.length; i++) {
            data[i] = (i * 7 + 3) & 0xff;
        }
        int[] ec = ReedSolomon.encode(data, 16);
        int[] block = new int[48];
        System.arraycopy(data, 0, block, 0, 32);
        System.arraycopy(ec, 0, block, 32, 16);

        int[] one = block.clone();
        one[5] ^= 0xff;
        assertArrayEquals(block, ReedSolomon.correct(one, 16), "单错纠正");

        int[] two = block.clone();
        two[5] ^= 0x01;
        two[40] ^= 0x80;
        assertArrayEquals(block, ReedSolomon.correct(two, 16), "双错纠正");

        int[] none = block.clone();
        assertArrayEquals(block, ReedSolomon.correct(none, 16), "无错直返副本");
    }

    @Test
    void rsUnrecoverableRejected() {
        int[] data = new int[12];
        for (int i = 0; i < data.length; i++) {
            data[i] = (i * 31 + 11) & 0xff;
        }
        int[] ec = ReedSolomon.encode(data, 7);
        int[] block = new int[19];
        System.arraycopy(data, 0, block, 0, 12);
        System.arraycopy(ec, 0, block, 12, 7);
        int[] broken = block.clone();
        broken[0] ^= 0x5a;
        broken[3] ^= 0xa5;
        broken[9] ^= 0x77;
        broken[15] ^= 0x33;
        assertThrows(IllegalStateException.class, () -> ReedSolomon.correct(broken, 7), "4 错超 t=3 能力拒绝");
    }

    // ---- CG1 模式与容量 ----

    @Test
    void segmentizerModesAndBitCounts() {
        assertEquals(List.of(new Segmentizer.Segment(Segmentizer.Mode.ALPHA, "HELLO WORLD "),
                        new Segmentizer.Segment(Segmentizer.Mode.DIGIT, "123")),
                Segmentizer.segmentize("HELLO WORLD 123"));
        assertEquals(Segmentizer.Mode.BYTE, Segmentizer.classOf('你'));

        assertEquals(24, Segmentizer.bitCount(List.of(new Segmentizer.Segment(Segmentizer.Mode.DIGIT, "123")), 1),
                "4 模式位 + 10 计数位 + 10 数据位");
        assertEquals(24, Segmentizer.bitCount(List.of(new Segmentizer.Segment(Segmentizer.Mode.ALPHA, "AB")), 1),
                "4 + 9 + 11");
        assertEquals(28, Segmentizer.bitCount(List.of(new Segmentizer.Segment(Segmentizer.Mode.BYTE, "ü")), 1),
                "4 + 8 + UTF-8 双字节 16");
        assertThrows(IllegalArgumentException.class, () -> Segmentizer.segmentize(""));
    }

    @Test
    void capacityVersionSelectionAndOverflow() {
        Segmentizer.Bitstream fit = Segmentizer.fit("HELLO WORLD", Segmentizer.EcLevel.L);
        assertEquals(1, fit.version(), "11 字母 74bit 容入 v1-L 152bit");
        assertEquals(19, fit.dataCodewords());
        assertEquals(152, fit.bits().size(), "补齐至容量位长");

        Segmentizer.Bitstream bigger = Segmentizer.fit(
                "ABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789012345", Segmentizer.EcLevel.Q);
        assertTrue(bigger.version() > 1, "长文本选更大版本");

        assertThrows(IllegalArgumentException.class, () -> Segmentizer.fit(
                "无法容纳的超长文本。".repeat(60), Segmentizer.EcLevel.H), "超容拒绝");
    }

    // ---- CG4 矩阵放置与 BCH ----

    @Test
    void matrixFunctionPatterns() {
        QrMatrix matrix = new QrMatrix(2);
        assertEquals(25, matrix.size);
        assertTrue(matrix.isDark(0, 0), "finder 左上");
        assertTrue(matrix.isDark(0, 24), "finder 右上");
        assertTrue(matrix.isDark(24, 0), "finder 左下");
        assertFalse(matrix.isDark(7, 0), "separator 浅色");
        assertTrue(matrix.isReserved(24, 7), "finder 区保留");
        assertFalse(matrix.isDark(6, 7), "timing 交替起浅");
        assertTrue(matrix.isDark(6, 8), "timing 交替");
        assertTrue(matrix.isReserved(18, 18), "v2 alignment 中心保留");
        assertTrue(matrix.isDark(17, 8), "dark module（4*2+9=17 行 8 列）");
        assertTrue(matrix.isReserved(8, 8), "格式区保留");
    }

    @Test
    void bch15And18AndFormatRoundTrip() {
        assertEquals(0, QrMatrix.bch15(0));
        assertEquals(0x07c94, QrMatrix.bch18(7), "版本 7 标准常数");
        for (int ec = 0; ec < 4; ec++) {
            for (int mask = 0; mask < 8; mask++) {
                int data = (ec << 3) | mask;
                int code = QrMatrix.bch15(data);
                int check = code;
                for (int i = 14; i >= 10; i--) {
                    if (((check >> i) & 1) == 1) {
                        check ^= 0x537 << (i - 10);
                    }
                }
                assertEquals(0, check, "32 组格式码字均可被生成多项式整除");
            }
        }
        assertThrows(IllegalArgumentException.class, () -> QrMatrix.bch15(32));
        assertThrows(IllegalArgumentException.class, () -> new QrMatrix(5));
    }

    // ---- CG5 掩码与罚分 ----

    @Test
    void maskFormulasAndPenalties() {
        assertTrue(Masking.isMasked(0, 0, 0));
        assertFalse(Masking.isMasked(1, 1, 0), "奇行不掩");
        assertTrue(Masking.isMasked(2, 0, 3), "3 倍列掩");
        assertTrue(Masking.isMasked(3, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> Masking.isMasked(8, 0, 0));

        boolean[][] solid = new boolean[10][10];
        for (boolean[] row : solid) {
            Arrays.fill(row, true);
        }
        assertEquals(0, Masking.penalty(solid) % 1);
        assertTrue(Masking.penalty(solid) > 60, "全暗矩阵 N1+N2+N4 高罚分");
        int before = Masking.penalty(solid);
        solid[0][0] = false;
        assertTrue(Masking.penalty(solid) != before, "扰动改变罚分");

        boolean[][] chess = new boolean[8][8];
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                chess[i][j] = (i + j) % 2 == 0;
            }
        }
        assertEquals(0, Masking.n2(chess), "棋盘无 2x2 同色");
        assertTrue(Masking.n1(chess) == 0, "棋盘无长游程");
    }

    // ---- CG6/CG7 端到端 ----

    @Test
    void encoderDeterministicAndRenderText() {
        QrEncoder.Result first = QrEncoder.encode("hello, world!", Segmentizer.EcLevel.M);
        QrEncoder.Result second = QrEncoder.encode("hello, world!", Segmentizer.EcLevel.M);
        assertEquals(first.version(), second.version());
        assertEquals(first.mask(), second.mask(), "掩码选优确定性");
        assertTrue(first.mask() >= 0 && first.mask() <= 7);
        assertArrayEquals(first.modules(), second.modules(), "输出确定性");

        String text = QrEncoder.renderText(first.modules(), 4);
        assertEquals(first.modules().length + 8, text.split("\n").length, "含静区行数");
        assertThrows(IllegalArgumentException.class, () -> QrEncoder.renderText(first.modules(), -1));
    }

    @Test
    void decoderRoundTripDigitAlphaByte() {
        QrEncoder.Result digits = QrEncoder.encode("12345678901234567890", Segmentizer.EcLevel.L);
        assertEquals("12345678901234567890", QrDecoder.decode(digits.modules()).text(), "数字模式往返");

        QrEncoder.Result alpha = QrEncoder.encode("HELLO WORLD 123", Segmentizer.EcLevel.Q);
        assertEquals("HELLO WORLD 123", QrDecoder.decode(alpha.modules()).text(), "字母+切换分段往返");

        QrEncoder.Result bytes = QrEncoder.encode("你好, QR!", Segmentizer.EcLevel.M);
        assertEquals("你好, QR!", QrDecoder.decode(bytes.modules()).text(), "字节模式 UTF-8 往返");
        assertTrue(QrDecoder.decode(bytes.modules()).version() >= 1, "版本信息可读");
    }

    @Test
    void decoderErrorCorrectionRecoversAndRejects() {
        QrEncoder.Result result = QrEncoder.encode("REED-SOLOMON POWER", Segmentizer.EcLevel.L);
        boolean[][] damaged = new boolean[result.modules().length][];
        for (int i = 0; i < damaged.length; i++) {
            damaged[i] = result.modules()[i].clone();
        }
        QrMatrix scaffold = new QrMatrix(result.version());
        int flipped = 0;
        for (int row = 0; row < damaged.length && flipped < 2; row++) {
            for (int col = 0; col < damaged.length && flipped < 2; col++) {
                if (!scaffold.isReserved(row, col)) {
                    damaged[row][col] = !damaged[row][col];
                    flipped++;
                }
            }
        }
        assertEquals(2, flipped);
        QrDecoder.Decoded decoded = QrDecoder.decode(damaged);
        assertEquals("REED-SOLOMON POWER", decoded.text(), "双模块损伤被 RS 纠正");
        assertTrue(decoded.corrected() >= 1, "纠正计数（两模块可能同码字）");

        boolean[][] formatBroken = new boolean[result.modules().length][];
        for (int i = 0; i < formatBroken.length; i++) {
            formatBroken[i] = result.modules()[i].clone();
        }
        formatBroken[8][0] = !formatBroken[8][0];
        assertThrows(IllegalArgumentException.class, () -> QrDecoder.decode(formatBroken), "格式 BCH 校验失败拒绝");

        assertThrows(IllegalArgumentException.class, () -> QrDecoder.decode(new boolean[10][10]), "尺寸非法拒绝");
    }
}
