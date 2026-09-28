package cn.chyuan.ai.domain.prettierkernel.service;

import java.util.List;

/**
 * doc IR 原语（工单 0986 EG3 / 0987 EG4，prettier 思想）。
 * text/line/softline/hardline/group/indent/concat 构造与拼接；fits 展平判定含硬换行必断。
 */
abstract class Doc {

    static Doc text(String value) {
        return new Text(value);
    }

    static Doc line() {
        return new Line(false, false);
    }

    static Doc softline() {
        return new Line(true, false);
    }

    static Doc hardline() {
        return new Line(false, true);
    }

    static Doc group(Doc inner) {
        return new Group(inner);
    }

    static Doc indent(Doc inner) {
        return new Indent(inner);
    }

    static Doc concat(List<Doc> docs) {
        return new Concat(List.copyOf(docs));
    }

    static Doc concat(Doc... docs) {
        return new Concat(List.of(docs));
    }
}

final class Text extends Doc {
    final String value;

    Text(String value) {
        this.value = value;
    }
}

final class Line extends Doc {
    final boolean soft;
    final boolean hard;

    Line(boolean soft, boolean hard) {
        this.soft = soft;
        this.hard = hard;
    }
}

final class Group extends Doc {
    final Doc inner;

    Group(Doc inner) {
        this.inner = inner;
    }
}

final class Indent extends Doc {
    final Doc inner;

    Indent(Doc inner) {
        this.inner = inner;
    }
}

final class Concat extends Doc {
    final List<Doc> docs;

    Concat(List<Doc> docs) {
        this.docs = docs;
    }
}

/** 展平与 fits 判定 */
final class Docs {

    private Docs() {
    }

    /** 展平：硬换行不可展平返回 null */
    static Doc flatten(Doc doc) {
        if (doc instanceof Text text) {
            return text;
        }
        if (doc instanceof Line line) {
            if (line.hard) {
                return null;
            }
            return Doc.text(line.soft ? "" : " ");
        }
        if (doc instanceof Group group) {
            return flatten(group.inner);
        }
        if (doc instanceof Indent indent) {
            return flatten(indent.inner);
        }
        Concat concat = (Concat) doc;
        java.util.List<Doc> parts = new java.util.ArrayList<>();
        for (Doc child : concat.docs) {
            Doc flat = flatten(child);
            if (flat == null) {
                return null;
            }
            parts.add(flat);
        }
        return Doc.concat(parts);
    }

    /** flat 文本宽度 */
    static int width(Doc doc) {
        Doc flat = flatten(doc);
        if (flat == null) {
            return Integer.MAX_VALUE;
        }
        return flatWidth(flat);
    }

    private static int flatWidth(Doc doc) {
        if (doc instanceof Text text) {
            return text.value.length();
        }
        if (doc instanceof Concat concat) {
            int total = 0;
            for (Doc child : concat.docs) {
                total += flatWidth(child);
            }
            return total;
        }
        if (doc instanceof Group group) {
            return flatWidth(group.inner);
        }
        if (doc instanceof Indent indent) {
            return flatWidth(indent.inner);
        }
        return 0;
    }

    /** fits：剩余宽度内能否按展平形态放下；含 line/hardline 必断 */
    static boolean fits(Doc doc, int remaining) {
        java.util.ArrayDeque<Doc> queue = new java.util.ArrayDeque<>();
        queue.add(doc);
        int width = remaining;
        while (!queue.isEmpty()) {
            Doc current = queue.poll();
            if (current instanceof Text text) {
                width -= text.value.length();
                if (width < 0) {
                    return false;
                }
            } else if (current instanceof Line line) {
                if (line.soft) {
                    continue;
                }
                return false;
            } else if (current instanceof Group group) {
                Doc flat = flatten(group.inner);
                if (flat == null) {
                    return false;
                }
                queue.addFirst(flat);
            } else if (current instanceof Indent indent) {
                queue.addFirst(indent.inner);
            } else if (current instanceof Concat concat) {
                List<Doc> docs = concat.docs;
                for (int i = docs.size() - 1; i >= 0; i--) {
                    queue.addFirst(docs.get(i));
                }
            }
        }
        return true;
    }
}
