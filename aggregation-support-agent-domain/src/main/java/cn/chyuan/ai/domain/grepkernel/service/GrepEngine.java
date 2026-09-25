package cn.chyuan.ai.domain.grepkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 检索引擎（工单 0856-0860 CW3-CW7，ripgrep 思想）。
 * 上下文分组与区段合并/行列统计/忽略规则 glob 子集/NUL 探测二进制跳过与强制文本/内存文件集排序确定性遍历。
 */
public final class GrepEngine {

    /** 单处命中 */
    public record Hit(String file, int lineNo, int col, int patternIndex, String lineText) {
    }

    /** 连续上下文区段 */
    public record Group(String file, int fromLine, int toLine, List<String> lines, List<Hit> hits) {
        public String render() {
            return String.join("\n", lines);
        }
    }

    /** 统计 */
    public record Stats(int totalMatches, Map<String, Integer> fileHits, List<String> skippedBinary,
                        List<String> ignoredPaths) {
    }

    /** 检索结果 */
    public record Result(List<Group> groups, List<Hit> hits, Stats stats) {
    }

    /** 选项：上下文行数/忽略 glob/强制文本 */
    public record Options(int before, int after, List<String> ignoreGlobs, boolean forceText) {
        public static Options defaults() {
            return new Options(0, 0, List.of(), false);
        }
    }

    private final GrepQuery query;
    private final Options options;

    public GrepEngine(GrepQuery query, Options options) {
        this.query = query;
        this.options = options;
    }

    /** 检索：排序确定性遍历 → 忽略 → 二进制 → 逐行 → 上下文分组 */
    public Result search(Map<String, List<String>> files) {
        List<Hit> hits = new ArrayList<>();
        List<String> skippedBinary = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        Map<String, Integer> fileHits = new LinkedHashMap<>();
        for (String path : new TreeMap<>(files).keySet()) {
            if (isIgnored(path)) {
                ignored.add(path);
                continue;
            }
            List<String> lines = files.get(path);
            if (!options.forceText() && isBinary(lines)) {
                skippedBinary.add(path);
                continue;
            }
            int count = 0;
            for (int i = 0; i < lines.size(); i++) {
                int patternIndex = query.matchIndex(lines.get(i));
                if (patternIndex >= 0) {
                    int col = query.findColumn(patternIndex, lines.get(i));
                    hits.add(new Hit(path, i + 1, col + 1, patternIndex, lines.get(i)));
                    count++;
                }
            }
            if (count > 0) {
                fileHits.put(path, count);
            }
        }
        return new Result(buildGroups(hits, files), hits,
                new Stats(hits.size(), fileHits, skippedBinary, ignored));
    }

    /** 上下文分组：命中行扩展前后 N 行，重叠区段合并 */
    private List<Group> buildGroups(List<Hit> hits, Map<String, List<String>> files) {
        List<Group> groups = new ArrayList<>();
        String currentFile = null;
        int groupFrom = -1;
        int groupTo = -1;
        List<Hit> groupHits = new ArrayList<>();
        for (Hit hit : hits) {
            int from = Math.max(1, hit.lineNo() - options.before());
            int to = Math.min(files.get(hit.file()).size(), hit.lineNo() + options.after());
            if (currentFile != null && (currentFile.equals(hit.file()) && from <= groupTo + 1)) {
                groupTo = Math.max(groupTo, to);
                groupHits.add(hit);
                continue;
            }
            if (currentFile != null) {
                groups.add(materialize(currentFile, groupFrom, groupTo, groupHits, files));
                groupHits = new ArrayList<>();
            }
            currentFile = hit.file();
            groupFrom = from;
            groupTo = to;
            groupHits.add(hit);
        }
        if (currentFile != null) {
            groups.add(materialize(currentFile, groupFrom, groupTo, groupHits, files));
        }
        return groups;
    }

    private Group materialize(String file, int from, int to, List<Hit> hits, Map<String, List<String>> files) {
        List<String> lines = new ArrayList<>();
        for (int n = from; n <= to; n++) {
            lines.add(files.get(file).get(n - 1));
        }
        return new Group(file, from, to, List.copyOf(lines), List.copyOf(hits));
    }

    /** NUL 字符判二进制 */
    static boolean isBinary(List<String> lines) {
        for (String line : lines) {
            if (line.indexOf('\0') >= 0) {
                return true;
            }
        }
        return false;
    }

    /** glob 子集：* 任意段 ? 单字符；无通配符时按路径前缀或文件名精确匹配 */
    boolean isIgnored(String path) {
        for (String glob : options.ignoreGlobs()) {
            if (glob.indexOf('*') >= 0 || glob.indexOf('?') >= 0) {
                String regex = glob.replace(".", "\\.").replace("*", ".*").replace("?", ".");
                if (path.matches(regex) || path.matches(".*/" + regex)) {
                    return true;
                }
            } else if (path.startsWith(glob) || path.equals(glob) || path.endsWith("/" + glob)) {
                return true;
            }
        }
        return false;
    }
}
