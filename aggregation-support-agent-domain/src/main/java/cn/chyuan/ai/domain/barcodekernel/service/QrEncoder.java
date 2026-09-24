package cn.chyuan.ai.domain.barcodekernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 编码器（工单 0721 CG6，zxing 思想）。
 * 端到端 文本→矩阵（纠错级可配）/静区留白/位矩阵文本渲染/确定性输出。
 */
public final class QrEncoder {

    private QrEncoder() {
    }

    /** 编码结果：模块矩阵 + 版本/纠错级/掩码 */
    public record Result(boolean[][] modules, int version, Segmentizer.EcLevel level, int mask) {
    }

    /** 端到端编码：分段→位流→分块纠错→交织→布位→掩码选优→格式信息 */
    public static Result encode(String text, Segmentizer.EcLevel level) {
        Segmentizer.Bitstream fit = Segmentizer.fit(text, level);
        int[] data = Segmentizer.toCodewords(fit.bits());
        ReedSolomon.BlockSpec spec = Segmentizer.blockSpec(fit.version(), level);

        List<int[]> dataBlocks = new ArrayList<>();
        List<int[]> ecBlocks = new ArrayList<>();
        for (int b = 0; b < spec.blocks(); b++) {
            int[] block = new int[spec.dataPerBlock()];
            System.arraycopy(data, b * spec.dataPerBlock(), block, 0, spec.dataPerBlock());
            dataBlocks.add(block);
            ecBlocks.add(ReedSolomon.encode(block, spec.ecPerBlock()));
        }
        int[] codewords = spec.blocks() == 1
                ? concat(dataBlocks.get(0), ecBlocks.get(0))
                : ReedSolomon.interleave(dataBlocks, ecBlocks);

        QrMatrix matrix = new QrMatrix(fit.version());
        matrix.placeData(codewords);
        int mask = Masking.bestMask(matrix);
        matrix.applyMask(mask);
        matrix.setFormatInfo(level.ordinal(), mask);
        return new Result(matrix.modules(), fit.version(), level, mask);
    }

    /** 位矩阵文本渲染（█=暗，空格=浅），含静区 */
    public static String renderText(boolean[][] modules, int quietZone) {
        if (quietZone < 0) {
            throw new IllegalArgumentException("静区须 >= 0");
        }
        int size = modules.length + quietZone * 2;
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                boolean dark = r >= quietZone && c >= quietZone
                        && r < quietZone + modules.length && c < quietZone + modules.length
                        && modules[r - quietZone][c - quietZone];
                sb.append(dark ? '█' : '　');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static int[] concat(int[] a, int[] b) {
        int[] out = new int[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
