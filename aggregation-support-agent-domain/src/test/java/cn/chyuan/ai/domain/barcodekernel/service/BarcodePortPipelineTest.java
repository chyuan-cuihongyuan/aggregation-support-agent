package cn.chyuan.ai.domain.barcodekernel.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BarcodePort 组合管线测试（工单 0723 CG8，zxing 思想）。
 * encode/decode 统一编排/vcskernel 摘要文本只读联动形态（泛型入参不 import）/
 * 静区渲染/损伤恢复/barcode-kernel.enabled 默认关。
 */
class BarcodePortPipelineTest {

    @Test
    void portEncodeDecodeChainWithRender() {
        BarcodePort port = BarcodePort.inMemory();
        QrEncoder.Result result = port.encode("PORT-PIPELINE-42", Segmentizer.EcLevel.M);
        assertTrue(result.version() >= 1 && result.version() <= 4);

        QrDecoder.Decoded decoded = port.decode(result.modules());
        assertEquals("PORT-PIPELINE-42", decoded.text());
        assertEquals(result.version(), decoded.version());
        assertEquals(result.level(), decoded.level());

        String rendered = port.matrixText(result.modules(), 2);
        assertTrue(rendered.startsWith("　　　　"), "静区留白");
        assertTrue(rendered.contains("█"), "暗模块渲染");
    }

    @Test
    void portDigestPayloadLinkageShape() throws Exception {
        // vcskernel 只读联动形态：blob 摘要十六进制文本作载荷（形状数据，不 import vcskernel）
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] digest = sha.digest("blob-content".getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }

        BarcodePort port = BarcodePort.inMemory();
        QrEncoder.Result encoded = port.encodeDigest(hex.toString(), Segmentizer.EcLevel.L);
        assertTrue(encoded.version() >= 1, "64 hex 字节模式应可选版");
        assertEquals(hex.toString(), port.decode(encoded.modules()).text(), "摘要载荷往返一致");

        assertThrows(IllegalArgumentException.class, () -> port.encodeDigest("abc", Segmentizer.EcLevel.L),
                "奇长拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.encodeDigest("zz", Segmentizer.EcLevel.L),
                "非十六进制拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.encodeDigest("", Segmentizer.EcLevel.L));
    }

    @Test
    void portDamageRecoveryAcrossLevels() {
        BarcodePort port = BarcodePort.inMemory();
        String payload = "DAMAGE-RECOVERY";
        for (Segmentizer.EcLevel level : Segmentizer.EcLevel.values()) {
            QrEncoder.Result result = port.encode(payload, level);
            boolean[][] damaged = new boolean[result.modules().length][];
            for (int i = 0; i < damaged.length; i++) {
                damaged[i] = result.modules()[i].clone();
            }
            QrMatrix scaffold = new QrMatrix(result.version());
            int flipped = 0;
            for (int row = 0; row < damaged.length && flipped < 1; row++) {
                for (int col = damaged.length - 1; col >= 0 && flipped < 1; col--) {
                    if (!scaffold.isReserved(row, col)) {
                        damaged[row][col] = !damaged[row][col];
                        flipped++;
                    }
                }
            }
            assertEquals(payload, port.decode(damaged).text(), level + " 级单点损伤恢复");
        }
    }
}
