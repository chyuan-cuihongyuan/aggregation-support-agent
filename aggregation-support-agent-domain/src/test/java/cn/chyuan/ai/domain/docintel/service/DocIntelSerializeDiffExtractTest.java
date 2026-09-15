package cn.chyuan.ai.domain.docintel.service;

import cn.chyuan.ai.domain.docintel.model.valobj.LayoutBlockVO;
import cn.chyuan.ai.domain.docintel.adapter.port.IDocParsePort;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AV5-AV9 单测（工单 0391/0392/0393/0394/0395）：Markdown 序列化/指纹 diff/字段抽取/记分/解析端口。
 */
class DocIntelSerializeDiffExtractTest {

    private LayoutBlockVO block(String id, String type, String text) {
        return new LayoutBlockVO(id, 0, 0, 100, 20, type, text);
    }

    @Test
    void Markdown序列化标题段落表格图注与转义重放一致() {
        MarkdownSerializer serializer = new MarkdownSerializer(1);
        List<LayoutBlockVO> ordered = List.of(
                block("t", LayoutBlockVO.TITLE, "季度报告"),
                block("p", LayoutBlockVO.PARAGRAPH, "总量上升，其中|分隔数据"),
                block("cap", LayoutBlockVO.CAPTION, "图 1 趋势"));
        TableStructureReconstructor.Structure table = new TableStructureReconstructor(5, 10, true)
                .reconstruct(List.of(
                        new TableStructureReconstructor.Cell("h1", 0, 0, 100, 30, "月份"),
                        new TableStructureReconstructor.Cell("h2", 110, 0, 100, 30, "销量"),
                        new TableStructureReconstructor.Cell("d1", 0, 40, 100, 30, "一月"),
                        new TableStructureReconstructor.Cell("d2", 110, 40, 100, 30, "12")));
        Map<String, TableStructureReconstructor.Structure> tables = new HashMap<>();
        tables.put("tab", table);
        String md = serializer.serialize(List.of(ordered.get(0),
                new LayoutBlockVO("tab", 0, 0, 100, 20, LayoutBlockVO.TABLE, ""), ordered.get(1), ordered.get(2)), tables);
        assertTrue(md.startsWith("# 季度报告"), md);
        assertTrue(md.contains("|月份|销量|\n| --- | --- |"), md);
        assertTrue(md.contains("*图 1 趋势*"), md);
        assertTrue(md.contains("\\|"), md);
        // 重放一致
        assertEquals(md, serializer.serialize(List.of(ordered.get(0),
                new LayoutBlockVO("tab", 0, 0, 100, 20, LayoutBlockVO.TABLE, ""), ordered.get(1), ordered.get(2)), tables));
    }

    @Test
    void 指纹稳定性与三类diff操作() {
        DocumentFingerprinter fp = new DocumentFingerprinter();
        DocumentFingerprinter.LayoutDiffer differ = new DocumentFingerprinter.LayoutDiffer();
        // 归一稳定：空白/全角/大小写不敏感
        assertEquals(fp.fingerprint("Hello World"), fp.fingerprint("hello  ｗｏｒｌｄ"));
        // 同文档 diff 为空
        List<String> doc = List.of(fp.fingerprint("A"), fp.fingerprint("B"), fp.fingerprint("C"));
        assertTrue(differ.diff(doc, doc).isEmpty());
        // [A,B,C] → [B,C,A]：B/C 位移 MOVED，A 追加 ADDED+REMOVED
        List<String> moved = List.of(fp.fingerprint("B"), fp.fingerprint("C"), fp.fingerprint("A"));
        List<DocumentFingerprinter.LayoutDiffer.Op> ops = differ.diff(doc, moved);
        assertTrue(ops.stream().anyMatch(op -> DocumentFingerprinter.LayoutDiffer.MOVED.equals(op.type())));
        assertTrue(ops.stream().anyMatch(op -> DocumentFingerprinter.LayoutDiffer.ADDED.equals(op.type())));
        // 删除：[A,C] 相对 [A,B,C] 有 REMOVED
        List<String> removed = List.of(fp.fingerprint("A"), fp.fingerprint("C"));
        assertTrue(differ.diff(doc, removed).stream()
                .anyMatch(op -> DocumentFingerprinter.LayoutDiffer.REMOVED.equals(op.type())));
    }

    @Test
    void 字段抽取锚点定位方向与三态() {
        FieldExtractor right = new FieldExtractor(FieldExtractor.Direction.RIGHT);
        List<LayoutBlockVO> leaves = List.of(
                block("1", LayoutBlockVO.PARAGRAPH, "设备总数：42台"),
                block("2", LayoutBlockVO.PARAGRAPH, "安装日期 2024年6月"),
                block("3", LayoutBlockVO.PARAGRAPH, "状态："),
                block("4", LayoutBlockVO.PARAGRAPH, "运行中"));
        List<FieldExtractor.Extraction> results = right.extract(leaves, List.of(
                new FieldExtractor.FieldSchema("总数", "设备总数", FieldExtractor.NUMBER, null, true),
                new FieldExtractor.FieldSchema("日期", "安装日期", FieldExtractor.DATE, null, true),
                new FieldExtractor.FieldSchema("状态", "状态", FieldExtractor.ENUM, List.of("运行中", "停机"), true),
                new FieldExtractor.FieldSchema("缺失", "不存在锚", FieldExtractor.ANY, null, false)));
        assertEquals(FieldExtractor.Extraction.HIT, results.get(0).state());
        assertEquals("42台", results.get(0).value());
        assertEquals(FieldExtractor.Extraction.HIT, results.get(1).state());
        assertEquals("2024年6月", results.get(1).value());
        // 下方取值方向
        FieldExtractor below = new FieldExtractor(FieldExtractor.Direction.BELOW);
        var status = below.extract(leaves, List.of(
                new FieldExtractor.FieldSchema("状态", "状态", FieldExtractor.ENUM, List.of("运行中"), true))).get(0);
        assertEquals(FieldExtractor.Extraction.HIT, status.state());
        assertEquals("运行中", status.value());
        assertEquals("4", status.fromBlockId());
        // 枚举不匹配三态（右侧取值非枚举域）
        List<LayoutBlockVO> enumLeaves = List.of(
                block("9", LayoutBlockVO.PARAGRAPH, "备注：停机中"));
        var badEnum = right.extract(enumLeaves, List.of(
                new FieldExtractor.FieldSchema("状态", "备注", FieldExtractor.ENUM, List.of("运行中"), true))).get(0);
        assertEquals(FieldExtractor.Extraction.TYPE_MISMATCH, badEnum.state());
        // 锚点未命中
        assertEquals(FieldExtractor.Extraction.MISS, results.get(3).state());
    }

    @Test
    void 版面记分四指标加权评级与短板() {
        LayoutScorecard scorecard = LayoutScorecard.defaults();
        // 规整版面：覆盖充分、无重叠、顺序单调、有表完整
        List<LayoutBlockVO> clean = List.of(
                new LayoutBlockVO("1", 0, 0, 400, 300, LayoutBlockVO.TITLE, "标题"),
                new LayoutBlockVO("2", 0, 310, 400, 300, LayoutBlockVO.PARAGRAPH, "正文"));
        var good = scorecard.score(clean, 600, 800, 1, 1);
        assertEquals("A", good.grade());
        assertTrue(good.weaknesses().isEmpty());
        assertTrue(good.score() >= 0.85);
        // 恶化：高重叠 + 顺序乱 + 表不完整 → 短板说明
        List<LayoutBlockVO> messy = List.of(
                block("1", LayoutBlockVO.PARAGRAPH, "a"),
                new LayoutBlockVO("2", 0, 0, 100, 20, LayoutBlockVO.PARAGRAPH, "重叠块"));
        var bad = scorecard.score(messy, 600, 800, 2, 0);
        assertTrue(bad.score() < good.score());
        assertTrue(bad.weaknesses().size() >= 2);
        // 非法权重
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new LayoutScorecard(0, 0, 0, 0));
    }

    @Test
    void 解析端口录制回放与规则合成() {
        // 域内端口契约（infrastructure 侧 RecordedDocParser 同语义装配）
        IDocParsePort fake = (docRef, page) -> {
            if (docRef == null || docRef.isBlank() || page < 1) {
                throw new IllegalArgumentException("文档引用不可为空且页码从 1 起");
            }
            return new IDocParsePort.ParseResult(docRef, page,
                    List.of(new IDocParsePort.PageElement("e1", 50, 50, 200, 30, "TITLE", "标题")), 3L);
        };
        var out = fake.parse("oss://doc.pdf", 1);
        assertEquals(1, out.elements().size());
        assertEquals("TITLE", out.elements().get(0).type());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> fake.parse("", 0));
    }
}
