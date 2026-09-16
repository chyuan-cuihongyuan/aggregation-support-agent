package cn.chyuan.ai.domain.codeintel.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编辑会话检查点（工单 0433 AZ7，aider /checkpoints 思想）。
 * 文件→快照栈（每次编辑前 checkpoint）；undo 回滚/redo 前进；
 * 栈容量上限淘汰最旧；undo→redo 后内容与检查点重放等价。纯函数内核。
 */
public class EditSession {

    private final int capacity;
    private final Map<String, Deque<String>> undoStacks = new LinkedHashMap<>();
    private final Map<String, Deque<String>> redoStacks = new LinkedHashMap<>();

    public EditSession(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("检查点容量至少 1");
        }
        this.capacity = capacity;
    }

    /** 编辑前保存检查点；容量满淘汰最旧 */
    public synchronized void checkpoint(String file, String content) {
        Deque<String> stack = undoStacks.computeIfAbsent(file, k -> new ArrayDeque<>());
        while (stack.size() >= capacity) {
            stack.pollLast();
        }
        stack.push(content);
        redoStacks.computeIfAbsent(file, k -> new ArrayDeque<>()).clear();
    }

    /** undo：返回上一检查点内容；无检查点返回 null */
    public synchronized String undo(String file, String current) {
        Deque<String> undo = undoStacks.get(file);
        if (undo == null || undo.isEmpty()) {
            return null;
        }
        String previous = undo.pop();
        redoStacks.computeIfAbsent(file, k -> new ArrayDeque<>()).push(current);
        return previous;
    }

    /** redo：返回前进内容；无返回 null */
    public synchronized String redo(String file, String current) {
        Deque<String> redo = redoStacks.get(file);
        if (redo == null || redo.isEmpty()) {
            return null;
        }
        String next = redo.pop();
        undoStacks.get(file).push(current);
        return next;
    }

    /** 检查点深度 */
    public synchronized int depth(String file) {
        Deque<String> stack = undoStacks.get(file);
        return stack == null ? 0 : stack.size();
    }
}
