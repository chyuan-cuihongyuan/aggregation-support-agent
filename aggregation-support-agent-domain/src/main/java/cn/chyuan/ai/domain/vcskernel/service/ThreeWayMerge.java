package cn.chyuan.ai.domain.vcskernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 三方合并（工单 0615 BU6，git merge 思想）。
 * base/ours/theirs 行级对齐（锚点=两侧 LCS 同时命中的 base 行）/
 * 相同改动自动采用/双方同区域不同改动冲突标记块
 * （<<<<<<< ======= >>>>>>> 标记与 ours/theirs 标签样式可配置）/
 * 冲突计数/二进制内容拒绝合并。
 */
public final class ThreeWayMerge {

    /** 合并结果 */
    public record Result(List<String> lines, int conflicts) {

        public boolean clean() {
            return conflicts == 0;
        }
    }

    /** 冲突标记样式（标签可配置） */
    public record Markers(String begin, String separator, String end) {
    }

    public static final Markers DEFAULT_MARKERS = new Markers("<<<<<<<", "=======", ">>>>>>>");

    private ThreeWayMerge() {
    }

    /** 二进制检测（含 NUL 字节拒绝合并） */
    public static boolean binary(List<String> lines) {
        for (String line : lines) {
            if (line.indexOf('\0') >= 0) {
                return true;
            }
        }
        return false;
    }

    /** 三方合并（默认标记） */
    public static Result merge(List<String> base, List<String> ours, List<String> theirs) {
        return merge(base, ours, theirs, DEFAULT_MARKERS, "OURS", "THEIRS");
    }

    /**
     * 三方合并：锚点=两侧 LCS 同时命中的 base 行（锚点行三方一致原样保留），
     * 相邻锚点之间的区域按 ours/theirs 相对 base 的异同裁决——
     * 相同改动自动采用，两侧不同改动落冲突标记块。
     */
    public static Result merge(List<String> base, List<String> ours, List<String> theirs,
                               Markers markers, String oursLabel, String theirsLabel) {
        if (base == null || ours == null || theirs == null) {
            throw new IllegalArgumentException("三方不得为 null");
        }
        if (markers == null) {
            markers = DEFAULT_MARKERS;
        }
        if (binary(base) || binary(ours) || binary(theirs)) {
            throw new IllegalArgumentException("二进制内容拒绝合并");
        }
        Map<Integer, Integer> matchOurs = align(base, ours);
        Map<Integer, Integer> matchTheirs = align(base, theirs);
        List<String> out = new ArrayList<>();
        int conflicts = 0;
        int prevB = 0;
        int prevO = 0;
        int prevT = 0;
        for (int b = 0; b < base.size(); b++) {
            if (matchOurs.containsKey(b) && matchTheirs.containsKey(b)) {
                int oAnchor = matchOurs.get(b);
                int tAnchor = matchTheirs.get(b);
                conflicts += emitChunk(out, base.subList(prevB, b),
                        ours.subList(prevO, oAnchor), theirs.subList(prevT, tAnchor),
                        markers, oursLabel, theirsLabel);
                out.add(base.get(b));
                prevB = b + 1;
                prevO = oAnchor + 1;
                prevT = tAnchor + 1;
            }
        }
        conflicts += emitChunk(out, base.subList(prevB, base.size()),
                ours.subList(prevO, ours.size()), theirs.subList(prevT, theirs.size()),
                markers, oursLabel, theirsLabel);
        return new Result(out, conflicts);
    }

    /** 区域裁决：相同取一；一侧未改取另一侧；两侧皆改且不同落冲突 */
    private static int emitChunk(List<String> out, List<String> baseChunk, List<String> oursChunk,
                                 List<String> theirsChunk, Markers markers,
                                 String oursLabel, String theirsLabel) {
        if (oursChunk.equals(theirsChunk)) {
            out.addAll(oursChunk);
            return 0;
        }
        if (oursChunk.equals(baseChunk)) {
            out.addAll(theirsChunk);
            return 0;
        }
        if (theirsChunk.equals(baseChunk)) {
            out.addAll(oursChunk);
            return 0;
        }
        if (baseChunk.isEmpty() && oursChunk.isEmpty() && theirsChunk.isEmpty()) {
            return 0;
        }
        out.add(markers.begin() + " " + oursLabel);
        out.addAll(oursChunk);
        out.add(markers.separator());
        out.addAll(theirsChunk);
        out.add(markers.end() + " " + theirsLabel);
        return 1;
    }

    /** LCS 对齐：base 行号 → side 行号（EQUAL 对，保序） */
    private static Map<Integer, Integer> align(List<String> base, List<String> side) {
        List<LineDiff.Row> rows = LineDiff.diff(base, side);
        Map<Integer, Integer> out = new LinkedHashMap<>();
        int b = 0;
        int s = 0;
        for (LineDiff.Row row : rows) {
            switch (row.op()) {
                case EQUAL -> out.put(b++, s++);
                case DELETE -> b++;
                case INSERT -> s++;
            }
        }
        return out;
    }
}
