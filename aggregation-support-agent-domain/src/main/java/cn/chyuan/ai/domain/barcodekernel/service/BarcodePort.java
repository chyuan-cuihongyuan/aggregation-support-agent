package cn.chyuan.ai.domain.barcodekernel.service;

/**
 * 条码端口（工单 0723 CG8，zxing 思想）。
 * encode/decode 入口统一编排/与 vcskernel 只读联动（blob 摘要十六进制文本作载荷形态）/
 * barcode-kernel.enabled 默认关（开启才改变行为）。
 */
public interface BarcodePort {

    /** 端到端编码 */
    QrEncoder.Result encode(String text, Segmentizer.EcLevel level);

    /** 端到端解码 */
    QrDecoder.Decoded decode(boolean[][] modules);

    /** vcskernel 只读联动形态：blob 摘要十六进制文本直接作载荷编码（校验 hex 形状） */
    QrEncoder.Result encodeDigest(String digestHex, Segmentizer.EcLevel level);

    /** 位矩阵文本渲染（含静区） */
    String matrixText(boolean[][] modules, int quietZone);

    /** 内存实现 */
    static BarcodePort inMemory() {
        return new InMemoryBarcode();
    }
}

final class InMemoryBarcode implements BarcodePort {

    @Override
    public QrEncoder.Result encode(String text, Segmentizer.EcLevel level) {
        return QrEncoder.encode(text, level);
    }

    @Override
    public QrDecoder.Decoded decode(boolean[][] modules) {
        return QrDecoder.decode(modules);
    }

    @Override
    public QrEncoder.Result encodeDigest(String digestHex, Segmentizer.EcLevel level) {
        if (digestHex == null || digestHex.isEmpty() || digestHex.length() % 2 != 0) {
            throw new IllegalArgumentException("摘要须为偶长十六进制文本");
        }
        for (char c : digestHex.toCharArray()) {
            if (Character.digit(c, 16) < 0) {
                throw new IllegalArgumentException("摘要含非十六进制字符: " + c);
            }
        }
        return QrEncoder.encode(digestHex, level);
    }

    @Override
    public String matrixText(boolean[][] modules, int quietZone) {
        return QrEncoder.renderText(modules, quietZone);
    }
}
