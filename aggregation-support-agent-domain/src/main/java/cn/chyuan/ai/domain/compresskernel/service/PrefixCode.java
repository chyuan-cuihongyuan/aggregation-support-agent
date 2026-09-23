package cn.chyuan.ai.domain.compresskernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * 前缀编码（工单 0587 BR2，zstd 前缀编码思想）。
 * 字节频率统计/Huffman 树构建（平局按最小符号定序，确定性）/
 * 规范码表生成（按码长与符号序）/literal 流编码与解码位级往返。
 */
public final class PrefixCode {

    /** 结束符号（块流终结） */
    public static final int END_OF_BLOCK = 256;
    /** 符号总数（256 字节 + 结束符） */
    public static final int SYMBOLS = 257;

    /** 规范码表：每符号码长（0=未用），码值按（码长，符号）规范递增派生 */
    public record CanonicalCode(int[] lengths, int[] codes) {

        public CanonicalCode {
            if (lengths == null || lengths.length != SYMBOLS) {
                throw new IllegalArgumentException("码长表须为 " + SYMBOLS + " 项");
            }
        }

        public static CanonicalCode of(int[] lengths) {
            return new CanonicalCode(lengths, deriveCodes(lengths));
        }

        public int codeOf(int symbol) {
            if (lengths[symbol] == 0) {
                throw new IllegalArgumentException("符号 " + symbol + " 无码字");
            }
            return codes[symbol];
        }

        /** 规范码值：同长符号序递增，长升一位左移 */
        private static int[] deriveCodes(int[] lengths) {
            int[] codes = new int[SYMBOLS];
            int maxLen = 0;
            for (int len : lengths) {
                maxLen = Math.max(maxLen, len);
            }
            int code = 0;
            for (int len = 1; len <= maxLen; len++) {
                for (int s = 0; s < SYMBOLS; s++) {
                    if (lengths[s] == len) {
                        codes[s] = code;
                        code++;
                    }
                }
                code <<= 1;
            }
            return codes;
        }
    }

    /** 规范解码器：按位读入，首个命中（码长，码值）区间的符号即解出 */
    public static final class CanonicalDecoder {

        private final int[] firstCode = new int[32];
        private final int[] firstIndex = new int[32];
        private final int[] count = new int[32];
        private final int[] symbolsByLength = new int[SYMBOLS];
        private final int maxLen;

        public CanonicalDecoder(int[] lengths) {
            List<Integer> ordered = new ArrayList<>();
            for (int len = 1; len < 32; len++) {
                for (int s = 0; s < SYMBOLS; s++) {
                    if (lengths[s] == len) {
                        ordered.add(s);
                        count[len]++;
                    }
                }
            }
            int max = 0;
            for (int len : lengths) {
                max = Math.max(max, len);
            }
            this.maxLen = max;
            for (int i = 0; i < ordered.size(); i++) {
                symbolsByLength[i] = ordered.get(i);
            }
            int code = 0;
            int index = 0;
            for (int len = 1; len < 32; len++) {
                firstCode[len] = code;
                firstIndex[len] = index;
                code = (code + count[len]) << 1;
                index += count[len];
            }
        }

        /** 位读取器接口（由解码侧位流提供） */
        public interface BitReader {
            int readBit();
        }

        /** 逐位解码：返回符号；超长无命中抛错（损坏流拒绝） */
        public int decode(BitReader reader) {
            int code = 0;
            int len = 0;
            while (len < maxLen) {
                code = (code << 1) | reader.readBit();
                len++;
                if (count[len] > 0 && code >= firstCode[len] && code - firstCode[len] < count[len]) {
                    return symbolsByLength[firstIndex[len] + (code - firstCode[len])];
                }
            }
            throw new IllegalArgumentException("前缀码流损坏：无命中码字");
        }
    }

    private PrefixCode() {
    }

    /** 哈夫曼树节点（freq 平局按 minSymbol 定序） */
    private record Node(long freq, int minSymbol, Node left, Node right) {
    }

    /** 从频率表构建码长（平局最小符号优先，确定性） */
    public static int[] buildLengths(long[] freqs) {
        if (freqs == null || freqs.length != SYMBOLS) {
            throw new IllegalArgumentException("频率表须为 " + SYMBOLS + " 项");
        }
        int used = 0;
        for (long f : freqs) {
            if (f < 0) {
                throw new IllegalArgumentException("频率不得为负");
            }
            if (f > 0) {
                used++;
            }
        }
        if (used == 0) {
            throw new IllegalArgumentException("频率表全零：至少一个符号在用");
        }
        if (used == 1) {
            int[] lengths = new int[SYMBOLS];
            for (int s = 0; s < SYMBOLS; s++) {
                if (freqs[s] > 0) {
                    lengths[s] = 1;
                }
            }
            return lengths;
        }
        PriorityQueue<Node> heap = new PriorityQueue<>((a, b) -> {
            if (a.freq != b.freq) {
                return Long.compare(a.freq, b.freq);
            }
            return Integer.compare(a.minSymbol, b.minSymbol);
        });
        for (int s = 0; s < SYMBOLS; s++) {
            if (freqs[s] > 0) {
                heap.add(new Node(freqs[s], s, null, null));
            }
        }
        int[] depths = new int[SYMBOLS];
        while (heap.size() > 1) {
            Node a = heap.poll();
            Node b = heap.poll();
            List<Integer> leaves = new ArrayList<>();
            collect(a, leaves);
            collect(b, leaves);
            int min = Integer.MAX_VALUE;
            for (int leaf : leaves) {
                min = Math.min(min, leaf);
                depths[leaf]++;
            }
            heap.add(new Node(safeAdd(a.freq, b.freq), min, a, b));
        }
        return depths;
    }

    private static long safeAdd(long a, long b) {
        long r = a + b;
        return r < 0 ? Long.MAX_VALUE : r;
    }

    private static void collect(Node node, List<Integer> leaves) {
        if (node.left() == null && node.right() == null) {
            leaves.add(node.minSymbol());
            return;
        }
        collect(node.left(), leaves);
        collect(node.right(), leaves);
    }
}
