package cn.chyuan.ai.domain.prettierkernel.service;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 宽度适配打印机（工单 0988 EG5 / 0989 EG6，prettier 思想）。
 * 超宽断组未超宽展平/行宽可配/indent 层级累积换行加缩进/fill 分组填充按行装填。
 */
public final class Printer {

    private static final String NEWLINE = "\n";
    private final int lineWidth;
    private final int indentUnit;

    public Printer(int lineWidth, int indentUnit) {
        if (lineWidth <= 0 || indentUnit <= 0) {
            throw new IllegalArgumentException("打印参数非法: " + lineWidth + "/" + indentUnit);
        }
        this.lineWidth = lineWidth;
        this.indentUnit = indentUnit;
    }

    /** 打印：group 按 fits 展平/断行，indent 层级累积 */
    public String print(Doc doc) {
        StringBuilder out = new StringBuilder();
        render(doc, false, 0, lineWidth, out);
        return out.toString();
    }

    private void render(Doc doc, boolean broke, int level, int remaining, StringBuilder out) {
        if (doc instanceof Text text) {
            out.append(text.value);
            return;
        }
        if (doc instanceof Line line) {
            if (line.hard || broke) {
                out.append(NEWLINE).append(" ".repeat(Math.max(0, level) * indentUnit));
            } else if (line.soft) {
                out.append("");
            } else {
                out.append(" ");
            }
            return;
        }
        if (doc instanceof Group group) {
            Doc flat = Docs.flatten(group.inner);
            if (flat != null && Docs.fits(flat, remaining)) {
                render(group.inner, false, level, remaining, out);
            } else {
                render(group.inner, true, level, remaining, out);
            }
            return;
        }
        if (doc instanceof Indent indent) {
            render(indent.inner, broke, level + 1, remaining, out);
            return;
        }
        Concat concat = (Concat) doc;
        int cursor = remaining;
        for (Doc child : concat.docs) {
            render(child, broke, level, cursor, out);
            cursor = lineWidth - currentLineLength(out);
        }
    }

    private int currentLineLength(StringBuilder out) {
        int lastNewline = out.lastIndexOf(NEWLINE);
        return out.length() - (lastNewline + 1);
    }

    /** fill 分组填充：按行贪心装填，单项超宽独立成行 */
    public String fill(java.util.List<Doc> items) {
        StringBuilder out = new StringBuilder();
        int lineWidthNow = 0;
        for (int i = 0; i < items.size(); i++) {
            Doc item = items.get(i);
            int itemWidth = Docs.width(item);
            if (i == 0) {
                out.append(flatText(item));
                lineWidthNow = itemWidth;
                continue;
            }
            if (lineWidthNow + 1 + itemWidth <= lineWidth && itemWidth <= lineWidth) {
                out.append(" ").append(flatText(item));
                lineWidthNow += 1 + itemWidth;
            } else {
                out.append(NEWLINE).append(flatText(item));
                lineWidthNow = itemWidth;
            }
        }
        return out.toString();
    }

    private String flatText(Doc doc) {
        Doc flat = Docs.flatten(doc);
        if (flat == null) {
            throw new IllegalArgumentException("fill 项含硬换行");
        }
        StringBuilder out = new StringBuilder();
        appendFlat(flat, out);
        return out.toString();
    }

    private void appendFlat(Doc doc, StringBuilder out) {
        if (doc instanceof Text text) {
            out.append(text.value);
        } else if (doc instanceof Concat concat) {
            concat.docs.forEach(child -> appendFlat(child, out));
        } else if (doc instanceof Group group) {
            appendFlat(group.inner, out);
        } else if (doc instanceof Indent indent) {
            appendFlat(indent.inner, out);
        }
    }

    /** 展平串宽度（Deque 处理示意保留扩展点） */
    static int measure(Doc doc) {
        Deque<Doc> queue = new ArrayDeque<>();
        queue.add(doc);
        int total = 0;
        while (!queue.isEmpty()) {
            Doc current = queue.poll();
            if (current instanceof Text text) {
                total += text.value.length();
            } else if (current instanceof Concat concat) {
                concat.docs.forEach(queue::add);
            } else if (current instanceof Group group) {
                queue.addFirst(group.inner);
            } else if (current instanceof Indent indent) {
                queue.addFirst(indent.inner);
            }
        }
        return total;
    }
}
