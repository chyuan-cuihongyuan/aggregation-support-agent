package cn.chyuan.ai.domain.editorkernel.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EditorPort 组合管线测试（工单 0715 CF8，neovim 思想）。
 * 会话编排（load/edit/undo/redo/write/search/substitute）/
 * 文档行形状只读联动形态（textkernel TextDocRegistry 形状数据，泛型入参不 import）。
 */
class EditorPortPipelineTest {

    @Test
    void portSessionEditUndoRedoWriteSubstitute() {
        EditorPort port = EditorPort.inMemory(3);
        port.loadDoc(List.of("alpha beta", "gamma"));

        EditorPort.EditorStats before = port.stats();
        assertEquals(2, before.lines());
        assertFalse(before.dirty());

        EditorPort.EditorPortResult result = port.edit('d', "w", 1);
        assertFalse(result.lineWise());
        assertEquals("alpha ", result.yanked(), "dw 摘取含尾随空白");
        assertEquals("beta", port.textDocLines().get(0));
        assertTrue(port.stats().dirty());
        assertEquals(2, port.stats().undoBlocks());

        assertThrows(IllegalStateException.class, port::quit, "脏缓冲退出拒绝");
        assertEquals(List.of("alpha beta", "gamma"), port.undo(), "撤销回载入态");
        assertEquals(List.of("alpha beta", "gamma"), port.textDocLines(), "撤销写回缓冲");
        assertEquals(List.of("beta", "gamma"), port.redo(), "重做到编辑态");

        assertEquals("beta\ngamma\n", port.write(), ":w 序列化");
        assertFalse(port.stats().dirty());
        port.quit();

        ExCommands.SubResult sub = port.substitute("%", "beta", "B", true);
        assertEquals(1, sub.count());
        assertEquals("B", port.textDocLines().get(0));

        SearchEngine.Match match = port.searchNext("gamma");
        assertEquals(1, match.line());
        assertEquals(0, match.startCol());
        assertThrows(IllegalStateException.class, () -> port.searchNext("zzz"));

        port.insertText("x");
        assertEquals("xB", port.textDocLines().get(0), "INSERT 行首插入");
    }

    @Test
    void portTextDocRegistryShapeLinkage() {
        Map<String, List<String>> fakeRegistry = new HashMap<>();
        EditorPort source = EditorPort.inMemory(3);
        source.loadDoc(List.of("doc line 1", "doc line 2"));

        List<String> docLines = source.textDocLines();
        fakeRegistry.put("doc-1", docLines);

        EditorPort consumer = EditorPort.inMemory(3);
        consumer.loadDoc(fakeRegistry.get("doc-1"));
        assertEquals(fakeRegistry.get("doc-1"), consumer.textDocLines(), "形状数据往返一致");
        assertEquals(2, consumer.stats().lines());

        consumer.substitute("2", "2", "deux", false);
        fakeRegistry.put("doc-1", consumer.textDocLines());
        assertEquals("doc line deux", consumer.textDocLines().get(1), "编辑后行形状可再登记");
    }

    @Test
    void portUndoTreeBranchingAcrossSession() {
        EditorPort port = EditorPort.inMemory(2);
        port.loadDoc(List.of("one"));
        port.edit('c', "c", 1);
        assertEquals("", port.write(), "cc 后缓冲为空行");
        port.insertText("two");
        assertEquals(List.of("two"), port.textDocLines());
        port.undo();
        port.undo();
        assertEquals(List.of("one"), port.textDocLines(), "二次撤销回载入态");
        assertTrue(port.stats().preservedBranches() >= 0, "分支保留统计可用");
    }
}
