package cn.chyuan.ai.domain.codeintel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * search-replace 应用（工单 0429 AZ3，aider 编辑思想）。
 * 块清单逐块应用：SEARCH 精确唯一命中→替换；多命中拒绝（报告命中数）；
 * 未命中尝试行级空白宽容（逐行 strip 后滑动窗口唯一命中）仍失败入失败清单；
 * 幂等：SEARCH 已不存在且 REPLACE 恰好存在→跳过。纯函数。
 */
public class SearchReplaceApplier {

    /** 单块应用结果 */
    public record BlockResult(int startLine, boolean applied, boolean skipped, String detail) {
    }

    /** 应用结果 */
    public record Applied(String content, List<BlockResult> results, boolean allApplied) {
    }

    private final SearchReplaceParser parser = new SearchReplaceParser();

    public Applied apply(String content, String edits) {
        SearchReplaceParser.Parsed parsed = parser.parse(edits);
        if (!parsed.errors().isEmpty()) {
            throw new IllegalArgumentException("编辑文本解析失败: " + parsed.errors().get(0));
        }
        List<String> lines = toLines(content);
        List<BlockResult> results = new ArrayList<>();
        boolean allApplied = true;
        for (SearchReplaceParser.Block block : parsed.blocks()) {
            String search = block.search();
            String replace = block.replace();
            if (search.isEmpty()) {
                results.add(new BlockResult(block.startLine(), false, true, "空 SEARCH 由会话层处理"));
                continue;
            }
            int occurrences = countOccurrences(content, search);
            if (occurrences > 1) {
                allApplied = false;
                results.add(new BlockResult(block.startLine(), false, false, "多命中 " + occurrences + " 处拒绝"));
                continue;
            }
            if (occurrences == 1) {
                content = replaceFirst(content, search, replace);
                lines = toLines(content);
                results.add(new BlockResult(block.startLine(), true, false, "精确命中"));
                continue;
            }
            // 精确未命中：行级空白宽容（逐行 strip 滑窗唯一命中）
            int lineIndex = looseFind(lines, toLines(search));
            if (lineIndex >= 0) {
                List<String> replaceLines = toLines(replace);
                lines.subList(lineIndex, lineIndex + toLines(search).size()).clear();
                lines.addAll(lineIndex, replaceLines);
                content = String.join("\n", lines);
                results.add(new BlockResult(block.startLine(), true, false, "空白宽容命中"));
                continue;
            }
            // 幂等：SEARCH 已不存在且 REPLACE 恰好存在一次 → 已应用过
            if (countOccurrences(content, replace) == 1) {
                results.add(new BlockResult(block.startLine(), false, true, "幂等跳过"));
                continue;
            }
            allApplied = false;
            results.add(new BlockResult(block.startLine(), false, false, "未命中"));
        }
        return new Applied(content, results, allApplied);
    }

    /** 行级滑动窗口：search 每行 strip 后与窗口行 strip 相等才命中；须唯一 */
    private static int looseFind(List<String> lines, List<String> searchLines) {
        if (searchLines.isEmpty() || searchLines.size() > lines.size()) {
            return -1;
        }
        int found = -1;
        for (int start = 0; start + searchLines.size() <= lines.size(); start++) {
            boolean match = true;
            for (int i = 0; i < searchLines.size(); i++) {
                if (!lines.get(start + i).strip().equals(searchLines.get(i).strip())) {
                    match = false;
                    break;
                }
            }
            if (match) {
                if (found >= 0) {
                    return -1;
                }
                found = start;
            }
        }
        return found;
    }

    private static List<String> toLines(String text) {
        if (text.isEmpty()) {
            return new ArrayList<>();
        }
        String normalized = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        return new ArrayList<>(List.of(normalized.split("\n", -1)));
    }

    private static int countOccurrences(String content, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static String replaceFirst(String content, String search, String replace) {
        int index = content.indexOf(search);
        return content.substring(0, index) + replace + content.substring(index + search.length());
    }
}
