package cn.chyuan.ai.domain.grepkernel.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 内容检索内核测试（工单 0854-0861 CW1-CW8，ripgrep 思想）。
 * 字面量 smart case/正则/多模式任一/上下文分组合并/行列统计/忽略 glob/二进制跳过/排序确定/端口条目联动。
 */
class GrepKernelTest {

    private static Map<String, List<String>> files() {
        Map<String, List<String>> files = new LinkedHashMap<>();
        files.put("b.txt", List.of("alpha", "Beta GAMMA", "delta"));
        files.put("a.log", List.of("nothing here", "alpha twice alpha"));
        files.put("z.bin", List.of("has\0nul"));
        return files;
    }

    @Test
    void literalSmartCase() {
        GrepQuery.Pattern lower = GrepQuery.Literal.smart("alpha");
        assertTrue(lower.find("Alpha time") >= 0, "全小写模式大小写不敏感");
        GrepQuery.Pattern upper = GrepQuery.Literal.smart("Alpha");
        assertTrue(upper.find("Alpha time") >= 0, "含大写模式敏感命中");
        assertTrue(upper.find("alpha time") < 0, "含大写模式拒绝小写文本");
        assertThrows(IllegalArgumentException.class, () -> GrepQuery.Literal.smart(""));
    }

    @Test
    void regexPattern() {
        GrepQuery.Pattern digits = new GrepQuery.Regex("\\d+ packets");
        assertTrue(digits.find("dropped 12 packets") == 8);
        assertTrue(digits.find("no numbers") < 0);
        assertThrows(IllegalArgumentException.class, () -> new GrepQuery.Regex("[unclosed"),
                "非法正则拒绝");
    }

    @Test
    void multiPatternAnyAndRecord() {
        GrepQuery query = new GrepQuery()
                .add(GrepQuery.Literal.smart("alpha"))
                .add(new GrepQuery.Regex("GAM\\w+"));
        assertEquals(2, query.patternCount());
        assertEquals(0, query.matchIndex("alpha here"), "首模式命中序号");
        assertEquals(1, query.matchIndex("the GAMMA line"), "第二模式命中序号");
        assertEquals(-1, query.matchIndex("nothing"));
    }

    @Test
    void searchHitsColumnsAndOrder() {
        GrepQuery query = new GrepQuery().add(GrepQuery.Literal.smart("alpha"));
        GrepEngine engine = new GrepEngine(query, GrepEngine.Options.defaults());
        GrepEngine.Result result = engine.search(files());
        assertEquals(2, result.hits().size());
        assertEquals("a.log", result.hits().get(0).file(), "路径排序确定性");
        assertEquals(2, result.hits().get(0).lineNo(), "行号 1 基");
        assertEquals(1, result.hits().get(0).col(), "列号 1 基");
        assertEquals("b.txt", result.hits().get(1).file());
        assertEquals(1, result.hits().get(1).col());
        assertEquals(2, result.stats().totalMatches());
        assertFalse(result.stats().fileHits().containsKey("z.bin"));
    }

    @Test
    void contextGroupingAndMerge() {
        Map<String, List<String>> files = new LinkedHashMap<>();
        files.put("code.txt", List.of("l1", "l2", "l3", "MATCH", "l5", "l6", "l7", "MATCH", "l9", "l10"));
        GrepQuery query = new GrepQuery().add(GrepQuery.Literal.smart("match"));
        GrepEngine engine = new GrepEngine(query, new GrepEngine.Options(2, 2, List.of(), false));
        GrepEngine.Result result = engine.search(files);
        assertEquals(1, result.groups().size(), "上下文重叠区段合并");
        GrepEngine.Group group = result.groups().get(0);
        assertEquals(2, group.fromLine());
        assertEquals(10, group.toLine());
        assertEquals(9, group.lines().size());
        assertEquals("l2\nl3\nMATCH\nl5\nl6\nl7\nMATCH\nl9\nl10", group.render());
        GrepEngine engineFar = new GrepEngine(query, new GrepEngine.Options(0, 0, List.of(), false));
        GrepEngine.Result far = engineFar.search(files);
        assertEquals(2, far.groups().size(), "不相邻命中各自成组");
    }

    @Test
    void perFileStats() {
        GrepEngine engine = new GrepEngine(new GrepQuery().add(GrepQuery.Literal.smart("alpha")),
                GrepEngine.Options.defaults());
        GrepEngine.Result result = engine.search(files());
        assertEquals(1, result.stats().fileHits().get("a.log"));
        assertEquals(1, result.stats().fileHits().get("b.txt"));
        assertEquals(2, result.stats().totalMatches());
    }

    @Test
    void ignoreRules() {
        GrepEngine engine = new GrepEngine(new GrepQuery().add(GrepQuery.Literal.smart("alpha")),
                new GrepEngine.Options(0, 0, List.of("*.log", "vendor"), false));
        GrepEngine.Result result = engine.search(files());
        assertTrue(result.stats().ignoredPaths().contains("a.log"), "glob 忽略");
        assertTrue(result.hits().stream().noneMatch(h -> h.file().equals("a.log")));
        Map<String, List<String>> vendored = new LinkedHashMap<>();
        vendored.put("vendor/lib/x.txt", List.of("alpha"));
        vendored.put("src/x.txt", List.of("alpha"));
        GrepEngine prefixEngine = new GrepEngine(new GrepQuery().add(GrepQuery.Literal.smart("alpha")),
                new GrepEngine.Options(0, 0, List.of("vendor"), false));
        GrepEngine.Result prefixResult = prefixEngine.search(vendored);
        assertTrue(prefixResult.stats().ignoredPaths().contains("vendor/lib/x.txt"), "前缀忽略");
        assertEquals(1, prefixResult.hits().size(), "未忽略路径仍检索");
    }

    @Test
    void binarySkipAndForceText() {
        GrepEngine engine = new GrepEngine(new GrepQuery().add(GrepQuery.Literal.smart("nul")),
                GrepEngine.Options.defaults());
        GrepEngine.Result result = engine.search(files());
        assertTrue(result.stats().skippedBinary().contains("z.bin"), "NUL 探测判二进制跳过");
        assertTrue(result.hits().isEmpty());
        GrepEngine forced = new GrepEngine(new GrepQuery().add(GrepQuery.Literal.smart("nul")),
                new GrepEngine.Options(0, 0, List.of(), true));
        GrepEngine.Result forcedResult = forced.search(files());
        assertTrue(forcedResult.stats().skippedBinary().isEmpty(), "强制文本不跳过");
        assertEquals(1, forcedResult.hits().size());
    }

    @Test
    void emptyFileSetAndNoMatch() {
        GrepEngine engine = new GrepEngine(new GrepQuery().add(GrepQuery.Literal.smart("alpha")),
                GrepEngine.Options.defaults());
        assertTrue(engine.search(Map.of()).groups().isEmpty(), "空文件集");
        GrepEngine.Result none = engine.search(Map.of("x.txt", List.of("nothing")));
        assertTrue(none.groups().isEmpty(), "无命中无分组");
        assertEquals(0, none.stats().totalMatches());
    }

    @Test
    void portOrchestrationAndItemLinkage() {
        GrepPort port = GrepPort.inMemory();
        GrepEngine.Result result = port.search(files(), new GrepQuery().add(GrepQuery.Literal.smart("alpha")),
                GrepEngine.Options.defaults());
        assertEquals(2, result.stats().totalMatches());
        record Doc(String name, List<String> lines) {
        }
        List<Doc> docs = List.of(
                new Doc("gateway", List.of("gateway logs", "gateway metrics")),
                new Doc("eval", List.of("eval report")));
        List<Doc> matched = port.searchOver(docs, Doc::lines, d -> d,
                new GrepQuery().add(GrepQuery.Literal.smart("gateway")));
        assertEquals(1, matched.size());
        assertEquals("gateway", matched.get(0).name(), "泛型条目形状只读联动");
    }
}
