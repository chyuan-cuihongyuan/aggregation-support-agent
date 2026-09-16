package cn.chyuan.ai.domain.codeintel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * search-replace 块解析（工单 0428 AZ2，aider search-replace 思想）。
 * {@code <<<<<<< SEARCH} / {@code ======="} / {@code >>>>>>> REPLACE} 多块解析；
 * 空 SEARCH 允许（新建语义）需空 REPLACE 分隔符独占行；未闭合/错序/无分隔拒绝并报告行号。
 */
public class SearchReplaceParser {

    private static final String HEAD = "<<<<<<< SEARCH";
    private static final String MID = "=======";
    private static final String TAIL = ">>>>>>> REPLACE";

    /** 解析块 */
    public record Block(int startLine, String search, String replace) {
    }

    /** 解析结果 */
    public record Parsed(List<Block> blocks, List<String> errors) {
    }

    public Parsed parse(String text) {
        List<Block> blocks = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (text == null || text.isBlank()) {
            errors.add("文本为空");
            return new Parsed(blocks, errors);
        }
        String[] lines = text.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = stripEol(lines[i]);
            if (!HEAD.equals(line.stripLeading())) {
                i++;
                continue;
            }
            int startLine = i + 1;
            int sep = -1;
            int end = -1;
            StringBuilder search = new StringBuilder();
            StringBuilder replace = new StringBuilder();
            int j = i + 1;
            for (; j < lines.length; j++) {
                String current = stripEol(lines[j]);
                if (MID.equals(current.stripLeading())) {
                    sep = j;
                    break;
                }
                search.append(current).append('\n');
            }
            if (sep < 0) {
                errors.add("未闭合：SEARCH 块缺分隔符（起始行 " + startLine + "）");
                break;
            }
            for (j = sep + 1; j < lines.length; j++) {
                String current = stripEol(lines[j]);
                if (TAIL.equals(current.stripLeading())) {
                    end = j;
                    break;
                }
                replace.append(current).append('\n');
            }
            if (end < 0) {
                errors.add("未闭合：REPLACE 块缺结束标记（起始行 " + startLine + "）");
                break;
            }
            blocks.add(new Block(startLine, search.toString(), replace.toString()));
            i = end + 1;
        }
        return new Parsed(blocks, errors);
    }

    private static String stripEol(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }
}
