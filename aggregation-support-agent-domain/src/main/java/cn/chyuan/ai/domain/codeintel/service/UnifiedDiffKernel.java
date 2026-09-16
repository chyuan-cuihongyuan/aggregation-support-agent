package cn.chyuan.ai.domain.codeintel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * unified diff 解析与应用（工单 0430 AZ4，aider/git diff 思想）。
 * {@code --- a/…}/{@code +++ b/…}/{@code @@ -x,n +y,m @@} hunk 解析（增/删/上下文行）→
 * 行号漂移容差应用（期望位置附近窗口找上下文唯一匹配）→反向 apply（交换增删）往返等价；
 * hunk 头错序/计数不符拒绝。纯函数。
 */
public class UnifiedDiffKernel {

    /** hunk */
    public record Hunk(int oldStart, int oldCount, int newStart, int newCount, List<String> lines) {
    }

    /** 解析产物 */
    public record Diff(String oldFile, String newFile, List<Hunk> hunks) {
    }

    private static final Pattern HUNK_HEAD = Pattern.compile(
            "@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");

    public Diff parse(String diffText) {
        if (diffText == null || diffText.isBlank()) {
            throw new IllegalArgumentException("diff 为空");
        }
        String[] lines = diffText.split("\n", -1);
        String oldFile = null;
        String newFile = null;
        List<Hunk> hunks = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String line = stripCr(lines[i]);
            if (line.startsWith("--- ")) {
                oldFile = line.substring(4).strip();
                i++;
                continue;
            }
            if (line.startsWith("+++ ")) {
                newFile = line.substring(4).strip();
                i++;
                continue;
            }
            Matcher head = HUNK_HEAD.matcher(line);
            if (head.matches()) {
                int oldStart = Integer.parseInt(head.group(1));
                int oldCount = head.group(2) == null ? 1 : Integer.parseInt(head.group(2));
                int newStart = Integer.parseInt(head.group(3));
                int newCount = head.group(4) == null ? 1 : Integer.parseInt(head.group(4));
                List<String> body = new ArrayList<>();
                int seenOld = 0;
                int seenNew = 0;
                i++;
                while (i < lines.length && (seenOld < oldCount || seenNew < newCount)) {
                    String bodyLine = stripCr(lines[i]);
                    if (bodyLine.startsWith("\\")) {
                        i++;
                        continue;
                    }
                    if (bodyLine.startsWith("+")) {
                        seenNew++;
                    } else if (bodyLine.startsWith("-")) {
                        seenOld++;
                    } else if (bodyLine.startsWith(" ") || bodyLine.isEmpty()) {
                        seenOld++;
                        seenNew++;
                    } else {
                        throw new IllegalArgumentException("hunk 体非法行 @" + (i + 1) + ": " + bodyLine);
                    }
                    body.add(bodyLine.isEmpty() ? " " : bodyLine);
                    i++;
                }
                if (seenOld != oldCount || seenNew != newCount) {
                    throw new IllegalArgumentException("hunk 计数不符（声明 -" + oldCount + " +" + newCount
                            + "，实际 -" + seenOld + " +" + seenNew + "）");
                }
                hunks.add(new Hunk(oldStart, oldCount, newStart, newCount, body));
                continue;
            }
            i++;
        }
        if (hunks.isEmpty()) {
            throw new IllegalArgumentException("diff 无 hunk");
        }
        // hunk 须按 oldStart 升序
        for (int k = 1; k < hunks.size(); k++) {
            if (hunks.get(k).oldStart() < hunks.get(k - 1).oldStart()) {
                throw new IllegalArgumentException("hunk 头错序");
            }
        }
        return new Diff(oldFile, newFile, hunks);
    }

    /** 应用 diff 到原文本；行号漂移容差=期望位置±window 内找上下文唯一匹配 */
    public String apply(String content, Diff diff, int driftWindow) {
        List<String> lines = toLines(content);
        int offset = 0;
        for (Hunk hunk : diff.hunks()) {
            int expected = hunk.oldStart() - 1 + offset;
            int anchor = findAnchor(lines, hunk, expected, Math.max(0, driftWindow));
            if (anchor < 0) {
                throw new IllegalArgumentException("hunk 上下文未命中（期望行 " + (expected + 1) + "）");
            }
            List<String> replacement = new ArrayList<>();
            for (String bodyLine : hunk.lines()) {
                if (bodyLine.startsWith(" ")) {
                    replacement.add(bodyLine.substring(1));
                } else if (bodyLine.startsWith("+")) {
                    replacement.add(bodyLine.substring(1));
                }
                // - 行丢弃
            }
            int removeCount = 0;
            for (String bodyLine : hunk.lines()) {
                if (bodyLine.startsWith(" ") || bodyLine.startsWith("-")) {
                    removeCount++;
                }
            }
            lines.subList(anchor, anchor + removeCount).clear();
            lines.addAll(anchor, replacement);
            offset += (anchor - expected) + replacement.size() - removeCount;
        }
        return String.join("\n", lines);
    }

    /** 反向 diff（交换 +/-） */
    public Diff reverse(Diff diff) {
        List<Hunk> reversed = new ArrayList<>();
        for (Hunk hunk : diff.hunks()) {
            List<String> lines = new ArrayList<>();
            for (String bodyLine : hunk.lines()) {
                if (bodyLine.startsWith("+")) {
                    lines.add("-" + bodyLine.substring(1));
                } else if (bodyLine.startsWith("-")) {
                    lines.add("+" + bodyLine.substring(1));
                } else {
                    lines.add(bodyLine);
                }
            }
            reversed.add(new Hunk(hunk.newStart(), hunk.newCount(), hunk.oldStart(), hunk.oldCount(), lines));
        }
        return new Diff(diff.newFile(), diff.oldFile(), reversed);
    }

    /** 在期望位置±窗口内找 hunk 旧块（上下文+删除行整体）唯一匹配点 */
    private static int findAnchor(List<String> lines, Hunk hunk, int expected, int window) {
        List<String> oldBlock = new ArrayList<>();
        for (String bodyLine : hunk.lines()) {
            if (bodyLine.startsWith(" ") || bodyLine.startsWith("-")) {
                oldBlock.add(bodyLine.substring(1));
            }
        }
        if (oldBlock.isEmpty()) {
            return Math.max(0, Math.min(expected, lines.size()));
        }
        int found = -1;
        for (int delta = 0; delta <= window; delta++) {
            for (int candidate : delta == 0 ? new int[]{expected}
                    : new int[]{expected + delta, expected - delta}) {
                if (candidate < 0 || candidate + oldBlock.size() > lines.size()) {
                    continue;
                }
                boolean match = true;
                for (int i = 0; i < oldBlock.size(); i++) {
                    if (!lines.get(candidate + i).equals(oldBlock.get(i))) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    found = candidate;
                    break;
                }
            }
            if (found >= 0) {
                break;
            }
        }
        return found;
    }

    private static List<String> toLines(String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(List.of(stripCr(text).split("\n", -1)));
    }

    private static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }
}
