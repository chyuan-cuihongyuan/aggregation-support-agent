package cn.chyuan.ai.domain.codeintel.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AZ1-AZ5 单测（工单 0427-0431）：仓库地图/search-replace 解析与应用/unified diff/代码切片。
 */
class CodeEditKernelTest {

    @Test
    void 仓库地图摘要与预算截断() {
        RepoMapper mapper = new RepoMapper();
        List<RepoMapper.MapLine> lines = mapper.mapLines(List.of(
                new RepoMapper.FileEntry("src/Foo.java", 42, "public class Foo {}\npublic interface Bar {}"),
                new RepoMapper.FileEntry("app.py", 10, "def main():\n    pass")));
        assertEquals(2, lines.size());
        assertEquals("java", lines.get(0).language());
        assertTrue(lines.get(0).symbols().contains("Foo"));
        assertTrue(lines.get(0).symbols().contains("Bar"));
        assertEquals("python", lines.get(1).language());
        // 预算截断（两行各约 26 字符，预算 40 只装得下一行）
        List<String> rendered = mapper.map(List.of(
                new RepoMapper.FileEntry("a/A.java", 100, "class A {}"),
                new RepoMapper.FileEntry("b/B.java", 100, "class B {}")), 40);        assertTrue(rendered.size() < 2 || rendered.get(rendered.size() - 1).contains("省略"));
    }

    @Test
    void searchReplace解析校验() {
        SearchReplaceParser parser = new SearchReplaceParser();
        String ok = """
                <<<<<<< SEARCH
                int a = 1;
                =======
                int a = 2;
                >>>>>>> REPLACE
                <<<<<<< SEARCH
                int b = 1;
                =======
                int b = 2;
                >>>>>>> REPLACE
                """;
        assertEquals(2, parser.parse(ok).blocks().size());
        assertTrue(parser.parse(ok).errors().isEmpty());
        // 未闭合
        assertFalse(parser.parse("<<<<<<< SEARCH\nint a;\n").errors().isEmpty());
        // 缺 REPLACE 结束
        assertFalse(parser.parse("<<<<<<< SEARCH\nint a;\n=======\nint b;\n").errors().isEmpty());
        // 空文本
        assertFalse(parser.parse("  \n").errors().isEmpty());
    }

    @Test
    void searchReplace应用幂等与多命中拒绝() {
        SearchReplaceApplier applier = new SearchReplaceApplier();
        String source = "int a = 1;\nint b = 1;\n";
        String edits = """
                <<<<<<< SEARCH
                int a = 1;
                =======
                int a = 2;
                >>>>>>> REPLACE
                """;
        var first = applier.apply(source, edits);
        assertTrue(first.allApplied());
        assertEquals("int a = 2;\nint b = 1;\n", first.content());
        // 幂等：再次应用 → 跳过
        var again = applier.apply(first.content(), edits);
        assertTrue(again.results().get(0).skipped());
        // 多命中拒绝
        var multi = applier.apply("int a = 1;\nint a = 1;\n", edits);
        assertFalse(multi.allApplied());
        assertTrue(multi.results().get(0).detail().contains("多命中"));
        // 空白宽容：行尾多空格命中
        var loose = applier.apply("int a = 1;   \n", edits);
        assertTrue(loose.allApplied());
        // 解析失败拒绝
        assertThrows(IllegalArgumentException.class, () -> applier.apply(source, "<<<<<<< SEARCH\n"));
    }

    @Test
    void unifiedDiff解析应用与往返等价() {
        UnifiedDiffKernel kernel = new UnifiedDiffKernel();
        String source = "line1\nline2\nline3\nline4\nline5\n";
        String diffText = """
                --- a/f.txt
                +++ b/f.txt
                @@ -2,3 +2,3 @@
                 line2
                -line3
                +LINE3
                 line4
                """;
        UnifiedDiffKernel.Diff diff = kernel.parse(diffText);
        assertEquals(1, diff.hunks().size());
        String applied = kernel.apply(source, diff, 0);
        assertEquals("line1\nline2\nLINE3\nline4\nline5\n", applied);
        // 反向 apply 往返等价
        String restored = kernel.apply(applied, kernel.reverse(kernel.parse(diffText)), 0);
        assertEquals(source, restored);
        // 漂移容差：期望位置偏移 1 行仍命中
        String shifted = "extra\n" + source;
        String appliedShifted = kernel.apply(shifted, diff, 2);
        assertTrue(appliedShifted.contains("LINE3"));
        // 计数不符拒绝
        assertThrows(IllegalArgumentException.class,
                () -> kernel.parse("--- a\n+++ b\n@@ -1,3 +1,3 @@\n line1\n"));
        // 无 hunk 拒绝
        assertThrows(IllegalArgumentException.class, () -> kernel.parse("--- a\n+++ b\n"));
    }

    @Test
    void 代码切片函数边界与窗口渲染() {
        CodeSlicer slicer = new CodeSlicer();
        String source = """
                header line
                public void foo() {
                    if (x) {
                        bar();
                    }
                }
                trailer line
                """;
        CodeSlicer.Slice slice = slicer.sliceContaining(source, 4);
        assertEquals(2, slice.startLine());
        assertEquals(6, slice.endLine());
        assertTrue(slice.lines().get(0).contains("foo"));
        // 不在函数内
        assertNull(slicer.sliceContaining(source, 1));
        // 行号前缀窗口
        String rendered = slicer.renderWithWindow(source, 4, 1);
        assertTrue(rendered.startsWith("1: header line"));
        assertTrue(rendered.contains("7: trailer line"));
    }
}
