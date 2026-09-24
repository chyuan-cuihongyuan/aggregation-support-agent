package cn.chyuan.ai.domain.editorkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 撤销树（工单 0709 CF2，neovim 思想）。
 * 编辑动作聚合 undo 块/undo·redo 沿当前分支/
 * undo 后编辑成新分支时间旅行/不可恢复兄弟分支保留/空操作不建块。
 */
public final class UndoTree {

    /** undo 块：行快照 + 父引用 + 有序子分支 */
    private static final class Node {
        final List<String> snapshot;
        final Node parent;
        final List<Node> children = new ArrayList<>();

        Node(List<String> snapshot, Node parent) {
            this.snapshot = snapshot;
            this.parent = parent;
        }
    }

    private final Node root;
    private Node current;

    public UndoTree(List<String> initialLines) {
        root = new Node(List.copyOf(initialLines), null);
        current = root;
    }

    /** 提交一个 undo 块：与当前快照相同则空操作不建块；undo 后提交产生新分支（兄弟保留） */
    public boolean checkpoint(List<String> lines) {
        List<String> snapshot = List.copyOf(lines);
        if (snapshot.equals(current.snapshot)) {
            return false;
        }
        Node node = new Node(snapshot, current);
        current.children.add(node);
        current = node;
        return true;
    }

    public boolean canUndo() {
        return current.parent != null;
    }

    public boolean canRedo() {
        return !current.children.isEmpty();
    }

    /** 撤销：回父块 */
    public List<String> undo() {
        if (!canUndo()) {
            throw new IllegalStateException("已在树根，无可撤销");
        }
        current = current.parent;
        return current.snapshot;
    }

    /** 重做：沿最近编辑分支前进 */
    public List<String> redo() {
        if (!canRedo()) {
            throw new IllegalStateException("已是叶节点，无可重做");
        }
        current = current.children.get(current.children.size() - 1);
        return current.snapshot;
    }

    /** 当前快照 */
    public List<String> current() {
        return current.snapshot;
    }

    /** 已建 undo 块总数（不含根） */
    public int blocks() {
        return count(root) - 1;
    }

    /** 不可恢复兄弟分支保留数：不在根→当前路径上的节点数 */
    public int preservedSiblings() {
        return blocks() - depth(current);
    }

    private static int count(Node node) {
        int n = 1;
        for (Node child : node.children) {
            n += count(child);
        }
        return n;
    }

    private static int depth(Node node) {
        int d = 0;
        for (Node p = node; p.parent != null; p = p.parent) {
            d++;
        }
        return d;
    }

    static boolean sameLines(List<String> a, List<String> b) {
        return Objects.equals(a, b);
    }
}
