package cn.chyuan.ai.domain.compresskernel.service;

import java.util.List;

/**
 * 压缩端口+组合管线（工单 0593 BR8）。
 * CompressPort（字节→压缩字节+统计摘要）组合管线：
 * 级别选择→LZ77+前缀编码成帧→解压校验往返；
 * 与 storekernel 只读联动（SSTable 段行序列化字节可选压缩形态，
 * 泛型入参不 import storekernel，不改 storekernel 任何类）/
 * compress-kernel.enabled 默认关（开启才改变行为）。
 */
public interface CompressPort {

    /** 压缩产出：帧 + 统计摘要 */
    record Outcome(byte[] frame, int level, long rawBytes, long compressedBytes, double ratio, long costMs) {
    }

    /** 压缩（指定级别，登记统计） */
    Outcome compress(String scene, byte[] input, int level);

    /** 压缩（自适应选级，登记统计） */
    Outcome compressAdaptive(String scene, byte[] input);

    /** 词典压缩（同词典解压，指纹校验） */
    Outcome compressWithDictionary(String scene, byte[] input, byte[] dict, int level);

    /** 解压（帧→原始字节；魔数/版本/词典指纹/CRC 校验失败拒绝） */
    byte[] decompress(byte[] frame, byte[] dict);

    /** 内容自适应选级（重复 4-gram 比率） */
    int suggestLevel(byte[] sample);

    /**
     * 与 storekernel 只读联动：把 SSTable 段行（键/序列号/值）序列化为
     * 字节并压缩，往返校验后返回帧——只读输入形态，不改 storekernel。
     */
    Outcome compressSegmentRows(String scene, List<String> segmentRows, int level);

    /** 统计登记访问（组合管线出口校验用） */
    CompressStatRegistry registry();

    /** 内存假实现：Lz77Matcher + PrefixCode + CompressCodec + CompressStatRegistry 全链 */
    class InMemoryCompressor implements CompressPort {

        private final CompressStatRegistry registry = new CompressStatRegistry();

        @Override
        public synchronized Outcome compress(String scene, byte[] input, int level) {
            return doCompress(scene, input, null, level, CompressStatRegistry.SOURCE_LEVEL);
        }

        @Override
        public synchronized Outcome compressAdaptive(String scene, byte[] input) {
            int level = suggestLevel(input);
            return doCompress(scene, input, null, level, CompressStatRegistry.SOURCE_ADAPTIVE);
        }

        @Override
        public synchronized Outcome compressWithDictionary(String scene, byte[] input, byte[] dict, int level) {
            return doCompress(scene, input, dict, level, "DICT");
        }

        @Override
        public synchronized byte[] decompress(byte[] frame, byte[] dict) {
            return CompressCodec.decompress(frame, dict);
        }

        @Override
        public synchronized int suggestLevel(byte[] sample) {
            return CompressionLevels.suggestLevel(sample);
        }

        @Override
        public synchronized Outcome compressSegmentRows(String scene, List<String> segmentRows, int level) {
            if (segmentRows == null) {
                throw new IllegalArgumentException("段行不得为 null");
            }
            StringBuilder sb = new StringBuilder();
            for (String row : segmentRows) {
                sb.append(row == null ? "" : row).append('\n');
            }
            byte[] input = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return doCompress(scene, input, null, level, "SST");
        }

        @Override
        public synchronized CompressStatRegistry registry() {
            return registry;
        }

        private Outcome doCompress(String scene, byte[] input, byte[] dict, int level, String remark) {
            if (input == null) {
                throw new IllegalArgumentException("输入不得为 null");
            }
            if (scene == null || scene.isBlank()) {
                throw new IllegalArgumentException("场景不得为空");
            }
            long start = System.nanoTime();
            CompressCodec.Compressed compressed = CompressCodec.compress(input, dict, level);
            byte[] roundtrip = CompressCodec.decompress(compressed.frame(), dict);
            if (!java.util.Arrays.equals(roundtrip, input)) {
                throw new IllegalStateException("压缩解压往返不一致：内核自校验失败");
            }
            long costMs = Math.max(0L, (System.nanoTime() - start) / 1_000_000L);
            registry.record(scene, level, compressed.rawBytes(), compressed.compressedBytes(),
                    costMs, System.currentTimeMillis(), remark);
            return new Outcome(compressed.frame(), level, compressed.rawBytes(),
                    compressed.compressedBytes(), compressed.ratio(), costMs);
        }
    }
}
