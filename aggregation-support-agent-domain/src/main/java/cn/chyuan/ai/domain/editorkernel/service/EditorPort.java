package cn.chyuan.ai.domain.editorkernel.service;

import java.util.List;

/**
 * 编辑器端口（工单 0715 CF8，neovim 思想）。
 * 会话入口（load/edit/undo/redo/write/search/substitute）统一编排/
 * textkernel 只读联动形态（textDocLines 文档行形状数据，不 import textkernel）/
 * editor-kernel.enabled 默认关（开启才改变行为）。
 */
public interface EditorPort {

    /** 载入文档（重建会话） */
    void loadDoc(List<String> lines);

    /** textkernel 只读联动形态：文档行形状数据 */
    List<String> textDocLines();

    /** 模态动作：operator+motion+count（d/c/y + h l w b e 0 $ gg G j k） */
    EditorPortResult edit(char operator, String motion, int count);

    /** 纯移动 */
    void move(String motion, int count);

    /** INSERT 模式插入文本（\n 换行） */
    void insertText(String text);

    /** 撤销/重做（返回当前行快照） */
    List<String> undo();

    List<String> redo();

    /** :w 写出（返回序列化文本并清脏标志） */
    String write();

    /** :q 脏检查 */
    void quit();

    /** 正向搜索 */
    SearchEngine.Match searchNext(String pattern);

    /** 反向搜索 */
    SearchEngine.Match searchPrev(String pattern);

    /** :s 替换 */
    ExCommands.SubResult substitute(String rangeSpec, String regex, String replacement, boolean global);

    /** 宏回放 */
    List<String> macroPlay(char reg, int count);

    /** 会话统计 */
    EditorStats stats();

    record EditorPortResult(int fromLine, int toLine, String yanked, boolean lineWise) {
    }

    record EditorStats(int lines, int undoBlocks, int preservedBranches, boolean dirty) {
    }

    /** 内存实现：组合各组件的默认会话 */
    static EditorPort inMemory(int autoJumpThreshold) {
        return new InMemoryEditor(autoJumpThreshold);
    }
}

/** 默认实现（同包内可见细节） */
final class InMemoryEditor implements EditorPort {

    private final Buffer buffer = new Buffer();
    private final UndoTree undoTree;
    private final Registers registers = new Registers();
    private final Modal modal;
    private final ExCommands ex;
    private final SearchEngine search;
    private final JumpList jumps;

    InMemoryEditor(int autoJumpThreshold) {
        undoTree = new UndoTree(buffer.snapshotLines());
        modal = new Modal(buffer);
        ex = new ExCommands(buffer);
        search = new SearchEngine(buffer);
        jumps = new JumpList(autoJumpThreshold);
    }

    @Override
    public void loadDoc(List<String> lines) {
        Buffer fresh = Buffer.load(String.join("\n", lines));
        List<String> snapshot = fresh.snapshotLines();
        restore(snapshot);
        undoTree.checkpoint(snapshot);
        ex.setCursorLine(0);
    }

    @Override
    public List<String> textDocLines() {
        return buffer.snapshotLines();
    }

    @Override
    public EditorPortResult edit(char operator, String motion, int count) {
        int before = modal.cursor().line();
        Modal.Result result = modal.operate(operator, motion, count);
        afterChange(result.yanked(), result.lineWise());
        if (result.yanked() != null && !result.yanked().isEmpty()) {
            registers.copy('r', result.yanked(),
                    result.lineWise() ? Registers.Type.LINE : Registers.Type.CHAR);
        }
        trackJump(before);
        return new EditorPortResult(
                result.range() == null ? 0 : result.range().startLine(),
                result.range() == null ? 0 : result.range().endLine(),
                result.yanked(),
                result.lineWise());
    }

    @Override
    public void move(String motion, int count) {
        int before = modal.cursor().line();
        modal.move(motion, count);
        trackJump(before);
    }

    @Override
    public void insertText(String text) {
        modal.enterInsert();
        for (int i = 0; i < text.codePointCount(0, text.length()); i++) {
            int cp = text.codePointAt(text.offsetByCodePoints(0, i));
            if (cp == '\n') {
                modal.insertNewline();
            } else {
                modal.insertCodePoint(cp);
            }
        }
        afterChange("", false);
    }

    @Override
    public List<String> undo() {
        List<String> snapshot = undoTree.undo();
        restore(snapshot);
        ex.touch();
        return snapshot;
    }

    @Override
    public List<String> redo() {
        List<String> snapshot = undoTree.redo();
        restore(snapshot);
        ex.touch();
        return snapshot;
    }

    @Override
    public String write() {
        return ex.write();
    }

    @Override
    public void quit() {
        ex.quit();
    }

    @Override
    public SearchEngine.Match searchNext(String pattern) {
        Modal.Cursor cursor = modal.cursor();
        return search.findNext(pattern, cursor.line(), cursor.col(), true);
    }

    @Override
    public SearchEngine.Match searchPrev(String pattern) {
        Modal.Cursor cursor = modal.cursor();
        return search.findPrev(pattern, cursor.line(), cursor.col(), true);
    }

    @Override
    public ExCommands.SubResult substitute(String rangeSpec, String regex, String replacement, boolean global) {
        return ex.substitute(rangeSpec, regex, replacement, global);
    }

    @Override
    public List<String> macroPlay(char reg, int count) {
        return registers.playback(reg, count);
    }

    @Override
    public EditorStats stats() {
        return new EditorStats(buffer.lineCount(), undoTree.blocks(),
                undoTree.preservedSiblings(), ex.dirty());
    }

    private void afterChange(String yanked, boolean lineWise) {
        undoTree.checkpoint(buffer.snapshotLines());
        ex.touch();
    }

    private void restore(List<String> snapshot) {
        while (buffer.lineCount() > 1) {
            buffer.removeLine(1);
        }
        buffer.setLine(0, snapshot.isEmpty() ? "" : snapshot.get(0));
        for (int i = 1; i < snapshot.size(); i++) {
            buffer.insertLine(i, snapshot.get(i));
        }
        modal.escape();
    }

    private void trackJump(int beforeLine) {
        int now = modal.cursor().line();
        if (jumps.shouldAutoJump(beforeLine, now) || Math.abs(now - beforeLine) > 0) {
            jumps.addJump(new JumpList.Pos(now, 0));
        }
    }
}
