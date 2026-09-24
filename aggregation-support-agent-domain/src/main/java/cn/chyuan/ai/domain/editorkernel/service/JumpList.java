package cn.chyuan.ai.domain.editorkernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 跳转与标记（工单 0714 CF7，neovim 思想）。
 * 单字母标记设置与跳转/跳转列表最近位置栈/大行位移自动入栈/标记随行编辑跟踪。
 */
public final class JumpList {

    /** 位置：行 0 基、列编码点 0 基 */
    public record Pos(int line, int col) {
    }

    /** 大行位移自动入栈阈值（可配） */
    private final int autoJumpThreshold;
    private final Map<Character, Pos> marks = new HashMap<>();
    private final List<Pos> jumps = new ArrayList<>();
    private int jumpIndex = -1;

    public JumpList(int autoJumpThreshold) {
        if (autoJumpThreshold < 0) {
            throw new IllegalArgumentException("阈值须 >= 0");
        }
        this.autoJumpThreshold = autoJumpThreshold;
    }

    // ---- 标记 ----

    public void setMark(char name, Pos pos) {
        checkMarkName(name);
        marks.put(name, pos);
    }

    public Pos mark(char name) {
        checkMarkName(name);
        return marks.get(name);
    }

    /** 行删除跟踪：被删行上的标记移除，其下标记上移 */
    public void trackLineRemoved(int at) {
        marks.replaceAll((k, v) -> {
            if (v.line() == at) {
                return null;
            }
            return v.line() > at ? new Pos(v.line() - 1, v.col()) : v;
        });
        marks.values().removeIf(v -> v == null);
    }

    /** 行插入跟踪：其下标记下移 */
    public void trackLineInserted(int at) {
        marks.replaceAll((k, v) -> v.line() >= at ? new Pos(v.line() + 1, v.col()) : v);
    }

    // ---- 跳转列表 ----

    /** 大行位移判定（gg/G 类自动入栈由调用方配合 shouldAutoJump 使用） */
    public boolean shouldAutoJump(int fromLine, int toLine) {
        return Math.abs(toLine - fromLine) >= autoJumpThreshold;
    }

    /** 跳转落点入栈：与栈顶相同则不重复 */
    public void addJump(Pos pos) {
        if (!jumps.isEmpty() && jumps.get(jumpIndex).equals(pos)) {
            return;
        }
        jumps.subList(jumpIndex + 1, jumps.size()).clear();
        jumps.add(pos);
        jumpIndex = jumps.size() - 1;
    }

    /** 旧位置（ctrl-o）：栈内回退 */
    public Pos older() {
        if (jumpIndex <= 0) {
            throw new IllegalStateException("跳转列表已到最早");
        }
        return jumps.get(--jumpIndex);
    }

    /** 新位置（ctrl-i）：栈内前进 */
    public Pos newer() {
        if (jumpIndex >= jumps.size() - 1) {
            throw new IllegalStateException("跳转列表已到最新");
        }
        return jumps.get(++jumpIndex);
    }

    public int size() {
        return jumps.size();
    }

    public Pos current() {
        if (jumpIndex < 0) {
            throw new IllegalStateException("跳转列表为空");
        }
        return jumps.get(jumpIndex);
    }

    private static void checkMarkName(char name) {
        if (!(name >= 'a' && name <= 'z')) {
            throw new IllegalArgumentException("非法标记名: " + name);
        }
    }
}
