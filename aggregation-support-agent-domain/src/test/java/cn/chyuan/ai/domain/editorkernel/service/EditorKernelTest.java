package cn.chyuan.ai.domain.editorkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 编辑器内核测试（工单 0708-0714 CF1-CF7，neovim 思想）。
 * 缓冲区编码点读写/撤销树分支时间旅行/寄存器与宏/模态动作组合/
 * Ex 命令与替换/搜索回绕/跳转列表与标记跟踪。
 */
class EditorKernelTest {

    // ---- CF1 缓冲区行模型 ----

    @Test
    void bufferLoadSerializeAndCodePointOps() {
        Buffer buffer = Buffer.load("ab\ncd");
        assertEquals(2, buffer.lineCount());
        assertEquals("ab", buffer.line(0));

        buffer.insertCodeAt(0, 1, 'X');
        assertEquals("aXb", buffer.line(0));
        buffer.deleteCodeAt(0, 1);
        assertEquals("ab", buffer.line(0));
        assertEquals('a', buffer.codePointAt(0, 0));

        buffer.splitLine(0, 1);
        assertEquals(List.of("a", "b", "cd"), buffer.snapshotLines());
        buffer.joinWithNext(1);
        assertEquals(List.of("a", "bcd"), buffer.snapshotLines());
        assertEquals("a\nbcd\n", buffer.serialize());
    }

    @Test
    void bufferMultiByteCodePointAndRejects() {
        Buffer buffer = Buffer.load("中文");
        assertEquals('文', buffer.codePointAt(0, 1), "按编码点取值非 char 偏移");
        buffer.insertCodeAt(0, 2, '!');
        assertEquals("中文!", buffer.line(0));

        assertThrows(IllegalArgumentException.class, () -> buffer.line(5));
        assertThrows(IllegalArgumentException.class, () -> buffer.codePointAt(0, 9));
        assertThrows(IllegalArgumentException.class, () -> buffer.insertCodeAt(0, -1, 'a'));
        assertThrows(IllegalArgumentException.class, () -> buffer.removeLine(0 + buffer.lineCount()));
        assertThrows(IllegalArgumentException.class, () -> buffer.joinWithNext(0));
    }

    @Test
    void bufferTrailingNewlineAndEmpty() {
        assertEquals(List.of("a"), Buffer.load("a\n").snapshotLines(), "结尾 \\n 不产生额外空行");
        assertEquals("", Buffer.load("").serialize());
        assertEquals(1, Buffer.load("").lineCount());
    }

    // ---- CF2 撤销树 ----

    @Test
    void undoTreeBranchTimeTravelPreservesSiblings() {
        UndoTree tree = new UndoTree(List.of("v0"));
        assertTrue(tree.checkpoint(List.of("v1")));
        assertTrue(tree.checkpoint(List.of("v2")));
        assertFalse(tree.checkpoint(List.of("v2")), "空操作不建块");
        assertEquals(2, tree.blocks());

        assertEquals("v1", tree.undo().get(0), "撤销回上一块");
        assertEquals("v0", tree.undo().get(0), "再撤销回根");
        assertTrue(tree.checkpoint(List.of("v1-alt")), "undo 后编辑成新分支");
        assertEquals(2, tree.preservedSiblings(), "v1·v2 兄弟分支保留");

        assertEquals("v0", tree.undo().get(0));
        assertTrue(tree.canRedo());
        assertEquals("v1-alt", tree.redo().get(0), "重做沿最近编辑分支");
        assertFalse(tree.canRedo());
        assertThrows(IllegalStateException.class, tree::redo);

        assertTrue(tree.canUndo());
        assertEquals("v0", tree.undo().get(0));
        assertThrows(IllegalStateException.class, tree::undo);
        assertEquals(3, tree.preservedSiblings(), "回到根后三块皆不在路径上");
    }

    // ---- CF3 寄存器与宏 ----

    @Test
    void registersCopyPasteAndMacroReplay() {
        Registers registers = new Registers();
        registers.copy('a', "hello", Registers.Type.CHAR);
        assertEquals(new Registers.Content(Registers.Type.CHAR, "hello"), registers.paste('a'));
        assertEquals("hello", registers.paste('"').text(), "匿名寄存器同步");
        registers.copy('a', "world", Registers.Type.LINE);
        assertEquals("world", registers.paste('a').text(), "覆盖式写入");

        registers.startRecord('q');
        registers.recordKey("dw");
        registers.recordKey("i");
        registers.stopRecord();
        assertTrue(registers.has('q'));
        assertEquals(List.of("dw", "i", "dw", "i"), registers.playback('q', 2), "回放 count 次");
        assertThrows(IllegalArgumentException.class, () -> registers.playback('q', 0));
        assertThrows(IllegalArgumentException.class, () -> registers.copy('1', "x", Registers.Type.CHAR));
        assertThrows(IllegalStateException.class, () -> registers.paste('z'), "空寄存器拒绝");
    }

    @Test
    void macroRecursivePlaybackRejected() {
        Registers registers = new Registers();
        registers.copy('a', "dw", Registers.Type.CHAR);
        registers.copy('b', "@a", Registers.Type.CHAR);
        assertEquals(List.of("dw"), registers.playback('b', 1), "@a 嵌套展开");
        registers.copy('c', "x\n@b\ny", Registers.Type.CHAR);
        List<String> flat = registers.playback('c', 1);
        assertEquals(List.of("x", "dw", "y"), flat, "嵌套宏内联展开");
        registers.copy('d', "@d", Registers.Type.CHAR);
        assertThrows(IllegalStateException.class, () -> registers.playback('d', 1), "自引用递归拒绝");
        registers.copy('e', "@e", Registers.Type.CHAR);
        registers.copy('f', "@e", Registers.Type.CHAR);
        registers.copy('e', "@f", Registers.Type.CHAR);
        assertThrows(IllegalStateException.class, () -> registers.playback('e', 1), "互引用嵌套拒绝");
    }

    // ---- CF4 模态动作 ----

    @Test
    void modalOperatorMotionCountComposition() {
        Buffer buffer = Buffer.load("hello world foo tail\nsecond line");
        Modal modal = new Modal(buffer);

        Modal.Result dw = modal.operate('d', "w", 1);
        assertEquals("hello ", dw.yanked(), "dw 删至词首含尾随空白");
        assertEquals("world foo tail", buffer.line(0));

        Modal.Result d2w = modal.operate('d', "w", 2);
        assertEquals("world foo ", d2w.yanked(), "count 倍乘");
        assertEquals("tail", buffer.line(0));

        Modal.Result ye = modal.operate('y', "e", 1);
        assertEquals("tail", ye.yanked(), "y 至词尾摘取");
        assertEquals("tail", buffer.line(0), "yank 不改缓冲");

        Modal.Result cc = modal.operate('c', "c", 1);
        assertTrue(cc.lineWise());
        assertEquals(Modal.Mode.INSERT, modal.mode(), "c 进 INSERT");
        modal.insertCodePoint('n');
        modal.insertNewline();
        modal.insertCodePoint('w');
        assertEquals(List.of("n", "wsecond line"), buffer.snapshotLines(), "INSERT 换行拆行后续写");
        modal.escape();
    }

    @Test
    void modalVisualAndLineOpsAndRejects() {
        Buffer buffer = Buffer.load("alpha beta gamma\nl2\nl3");
        Modal modal = new Modal(buffer);

        Modal.Result yy = modal.operate('y', "y", 1);
        assertEquals("alpha beta gamma\n", yy.yanked());
        assertTrue(yy.lineWise());

        modal.enterVisual(false);
        modal.visualExtend("w", 1);
        Modal.Result vd = modal.operate('d', "", 1);
        assertEquals("alpha ", vd.yanked(), "visual 选区删除含至下词首空白");
        assertEquals(Modal.Mode.NORMAL, modal.mode());
        assertEquals("beta gamma", buffer.line(0));

        assertThrows(IllegalArgumentException.class, () -> modal.operate('x', "w", 1), "非法 operator");
        assertThrows(IllegalArgumentException.class, () -> modal.operate('d', "z", 1), "非法 motion");
        assertThrows(IllegalArgumentException.class, () -> modal.operate('d', "w", 0), "非法 count");
        assertThrows(IllegalStateException.class, () -> modal.insertCodePoint('a'), "非 INSERT 拒绝插入");

        Modal g0 = new Modal(Buffer.load("abc"));
        g0.move("0", 1);
        assertEquals(0, g0.cursor().col());
    }

    // ---- CF5 Ex 命令 ----

    @Test
    void exWriteQuitRangeAndSubstitute() {
        Buffer buffer = Buffer.load("one two one\nthree\nfour one");
        ExCommands ex = new ExCommands(buffer);
        ex.touch();

        assertThrows(IllegalStateException.class, ex::quit, "脏缓冲 :q 拒绝");
        String written = ex.write();
        assertEquals("one two one\nthree\nfour one\n", written);
        assertFalse(ex.dirty());
        ex.quit();

        assertEquals(new ExCommands.LineRange(0, 2), ex.parseRange("%"));
        assertEquals(new ExCommands.LineRange(1, 2), ex.parseRange("2,3"));
        assertEquals(new ExCommands.LineRange(2, 2), ex.parseRange("$"));
        assertEquals(new ExCommands.LineRange(0, 1), ex.parseRange(".,+1"));
        assertThrows(IllegalArgumentException.class, () -> ex.parseRange("3,2"));
        assertThrows(IllegalArgumentException.class, () -> ex.parseRange("9"));
        assertThrows(IllegalArgumentException.class, () -> ex.parseRange(".,x"));

        ExCommands.SubResult sub = ex.substitute("%", "one", "ONE", false);
        assertEquals(2, sub.count(), "非 global 每行首处");
        assertEquals(List.of("ONE two one", "three", "four ONE"), buffer.snapshotLines());

        ExCommands.SubResult global = ex.substitute("%", "one", "9", true);
        assertEquals(1, global.count(), "global 行内全处");
        assertEquals("ONE two 9", buffer.line(0));

        assertThrows(IllegalStateException.class, () -> ex.substitute("", "zzz", "x", true), "无匹配拒绝");
    }

    // ---- CF6 搜索 ----

    @Test
    void searchWrapAndValidation() {
        Buffer buffer = Buffer.load("ab cd ab\nef ab");
        SearchEngine search = new SearchEngine(buffer);

        assertEquals(3, search.allMatches("ab", 10).size());
        SearchEngine.Match next = search.findNext("ab", 0, 3, true);
        assertEquals(6, next.startCol(), "同行下一个");
        SearchEngine.Match wrap = search.findNext("ab", 1, 3, true);
        assertEquals(0, wrap.line(), "尾部回绕到首行");

        SearchEngine.Match prev = search.findPrev("ab", 1, 3, true);
        assertEquals(0, prev.line(), "反向回到前一行");
        SearchEngine.Match prevWrap = search.findPrev("ab", 0, 0, true);
        assertEquals(1, prevWrap.line(), "头部回绕到末行");

        assertEquals(List.of(), search.allMatches("zzz", 10));
        assertThrows(IllegalStateException.class, () -> search.findNext("zzz", 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> search.compile("a[b"), "编译错误报定位");
        assertThrows(IllegalArgumentException.class, () -> search.compile(""), "空模式拒绝");
    }

    // ---- CF7 跳转与标记 ----

    @Test
    void jumpListMarksAndTracking() {
        JumpList jumps = new JumpList(3);
        assertFalse(jumps.shouldAutoJump(1, 2), "小位移不入栈");
        assertTrue(jumps.shouldAutoJump(1, 5), "大行位移自动入栈");

        jumps.addJump(new JumpList.Pos(0, 0));
        jumps.addJump(new JumpList.Pos(5, 0));
        jumps.addJump(new JumpList.Pos(9, 0));
        assertEquals(new JumpList.Pos(5, 0), jumps.older());
        assertEquals(new JumpList.Pos(9, 0), jumps.newer());
        assertThrows(IllegalStateException.class, jumps::newer);

        assertEquals(new JumpList.Pos(5, 0), jumps.older(), "回退到中部");
        jumps.addJump(new JumpList.Pos(2, 0));
        assertEquals(3, jumps.size(), "中部跳转截断未来分支");
        assertEquals(new JumpList.Pos(2, 0), jumps.current());
        assertThrows(IllegalStateException.class, jumps::newer);
        jumps.addJump(new JumpList.Pos(2, 0));
        assertEquals(3, jumps.size(), "栈顶重复落点去重");

        JumpList tracked = new JumpList(1);
        tracked.setMark('a', new JumpList.Pos(2, 0));
        assertEquals(new JumpList.Pos(2, 0), tracked.mark('a'));
        tracked.trackLineInserted(1);
        assertEquals(new JumpList.Pos(3, 0), tracked.mark('a'), "其下标记下移");
        tracked.trackLineRemoved(3);
        assertNull(tracked.mark('a'), "被删行标记移除");
        assertThrows(IllegalArgumentException.class, () -> tracked.setMark('1', new JumpList.Pos(0, 0)));
    }
}
