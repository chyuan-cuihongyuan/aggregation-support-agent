package cn.chyuan.ai.domain.codeintel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 仓库地图（工单 0427 AZ1，aider repo map 思想）。
 * 文件条目（路径/行数/内容样本）→语言后缀分类→目录聚合行数→
 * 每文件一行紧凑摘要（路径 + 行数 + 关键符号粗筛）+ 总量超限按目录预算截断省略。纯函数。
 */
public class RepoMapper {

    /** 文件条目入参 */
    public record FileEntry(String path, int lineCount, String contentSample) {
    }

    /** 摘要行 */
    public record MapLine(String path, String language, int lineCount, List<String> symbols) {
    }

    private static final Pattern SYMBOL = Pattern.compile(
            "(?m)^\\s*(?:public\\s+|private\\s+|protected\\s+|final\\s+|abstract\\s+)*"
                    + "(class|interface|enum|record|def|function|struct|impl)\\s+([A-Za-z_][A-Za-z0-9_]*)");

    /** 结构化摘要行（无预算） */
    public List<MapLine> mapLines(List<FileEntry> files) {
        List<MapLine> lines = new ArrayList<>();
        for (FileEntry file : files) {
            String language = languageOf(file.path());
            lines.add(new MapLine(file.path(), language, file.lineCount(), symbols(file.contentSample())));
        }
        return lines;
    }

    /** 生成仓库地图文本：每文件一行；总字符超 budget 时按目录聚合省略 */
    public List<String> map(List<FileEntry> files, int budget) {
        List<MapLine> lines = mapLines(files);
        Map<String, Integer> dirTotals = new LinkedHashMap<>();
        for (FileEntry file : files) {
            String dir = file.path().contains("/")
                    ? file.path().substring(0, file.path().lastIndexOf('/') + 1)
                    : "";
            dirTotals.merge(dir, file.lineCount(), Integer::sum);
        }
        List<String> out = new ArrayList<>();
        int used = 0;
        boolean truncated = false;
        for (MapLine line : lines) {
            String text = render(line);
            if (used + text.length() > budget && !out.isEmpty()) {
                truncated = true;
                break;
            }
            out.add(text);
            used += text.length();
        }
        if (truncated) {
            out.add("...（其余 " + (lines.size() - out.size()) + " 文件按目录省略："
                    + String.join(", ", dirTotals.keySet().stream().skip(Math.max(0, out.size() - 1))
                    .limit(3).toList()) + "）");
        }
        return out;
    }

    private String render(MapLine line) {
        return line.path() + " (" + line.language() + ", " + line.lineCount() + " 行)"
                + (line.symbols().isEmpty() ? "" : " :: " + String.join(", ", line.symbols()));
    }

    /** 语言后缀分类 */
    static String languageOf(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".java")) {
            return "java";
        }
        if (lower.endsWith(".py")) {
            return "python";
        }
        if (lower.endsWith(".js") || lower.endsWith(".ts")) {
            return "js/ts";
        }
        if (lower.endsWith(".go")) {
            return "go";
        }
        if (lower.endsWith(".md")) {
            return "markdown";
        }
        return "other";
    }

    /** 关键符号粗筛：class/interface/def/function 等声明行首匹配，最多 3 个 */
    static List<String> symbols(String contentSample) {
        if (contentSample == null || contentSample.isEmpty()) {
            return List.of();
        }
        List<String> symbols = new ArrayList<>();
        Matcher matcher = SYMBOL.matcher(contentSample);
        while (matcher.find() && symbols.size() < 3) {
            symbols.add(matcher.group(2));
        }
        return symbols;
    }
}
