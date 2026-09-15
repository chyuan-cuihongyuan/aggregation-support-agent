package cn.chyuan.ai.domain.docintel.service;

import cn.chyuan.ai.domain.docintel.model.valobj.LayoutBlockVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AV1-AV4 单测（工单 0387/0388/0389/0390）：XY-cut 版面树/阅读顺序/表格重组/OCR 融合。
 */
class DocIntelLayoutTest {

    private LayoutBlockVO block(String id, double x, double y, double w, double h, String type, String text) {
        return new LayoutBlockVO(id, x, y, w, h, type, text);
    }

    @Test
    void XYCut递归切分双栏与图文混排() {
        XYCutLayoutAnalyzer analyzer = new XYCutLayoutAnalyzer(10.0);
        // 通栏标题（y 0-40）+ 左右两栏（y 50-400）
        List<LayoutBlockVO> blocks = List.of(
                block("t", 50, 0, 500, 40, LayoutBlockVO.TITLE, "报告标题"),
                block("l1", 50, 50, 240, 200, LayoutBlockVO.PARAGRAPH, "左栏上"),
                block("l2", 50, 260, 240, 140, LayoutBlockVO.PARAGRAPH, "左栏下"),
                block("r1", 310, 50, 240, 340, LayoutBlockVO.PARAGRAPH, "右栏"));
        XYCutLayoutAnalyzer.Node root = analyzer.analyze(blocks);
        // 第一层 Y 切分（标题与正文间空白带）
        assertEquals("Y", root.getAxis());
        assertEquals(2, root.getChildren().size());
        // 第二层对正文区 X 切栏
        XYCutLayoutAnalyzer.Node body = root.getChildren().get(1);
        assertEquals("X", body.getAxis());
        assertEquals(2, body.getChildren().size());
        // 空页面拒绝
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(List.of()));
    }

    @Test
    void 阅读顺序通栏优先与栏序行序() {
        ReadingOrderSorter sorter = new ReadingOrderSorter(600, 0.8, 20);
        List<LayoutBlockVO> ordered = sorter.sort(List.of(
                block("col2-b", 310, 60, 240, 80, LayoutBlockVO.PARAGRAPH, "右下"),
                block("full", 50, 200, 500, 60, LayoutBlockVO.PARAGRAPH, "通栏"),
                block("col1-a", 50, 50, 240, 80, LayoutBlockVO.PARAGRAPH, "左上"),
                block("col1-b", 50, 150, 240, 40, LayoutBlockVO.PARAGRAPH, "左下"),
                block("col2-a", 310, 50, 240, 80, LayoutBlockVO.PARAGRAPH, "右上")));
        // 通栏元素宽度 500/600=0.83 ≥ 0.8 → 通栏优先（按 Y），其后逐栏
        assertEquals("full", ordered.get(0).getId());
        assertEquals("col1-a", ordered.get(1).getId());
        assertEquals("col2-b", ordered.get(4).getId());
        List<String> ids = ordered.stream().map(LayoutBlockVO::getId).toList();
        assertTrue(ids.indexOf("col1-a") < ids.indexOf("col1-b"));
        assertTrue(ids.indexOf("col2-a") < ids.indexOf("col2-b"));
    }

    @Test
    void 表格行列聚类表头与缺格补空() {
        TableStructureReconstructor reconstructor = new TableStructureReconstructor(5, 10, true);
        List<TableStructureReconstructor.Cell> cells = List.of(
                new TableStructureReconstructor.Cell("h1", 0, 0, 100, 30, "名称"),
                new TableStructureReconstructor.Cell("h2", 110, 0, 100, 30, "数量"),
                new TableStructureReconstructor.Cell("a1", 0, 40, 100, 30, "苹果"),
                new TableStructureReconstructor.Cell("a2", 110, 40, 100, 30, "3"),
                new TableStructureReconstructor.Cell("b1", 0, 80, 100, 30, "香蕉"));
        TableStructureReconstructor.Structure structure = reconstructor.reconstruct(cells);
        // 总行数 3（含表头行），数据行 2
        assertEquals(3, structure.rowCount());
        assertEquals(2, structure.rows().length);
        assertEquals(2, structure.colCount());
        // 表头=首行
        assertEquals("名称", structure.header()[0]);
        assertEquals("数量", structure.header()[1]);
        // 缺格补空：香蕉行数量格为空串
        assertEquals("", structure.rows()[1][1]);
        assertEquals("苹果", structure.rows()[0][0]);
        // 关闭表头启发
        TableStructureReconstructor noHeader = new TableStructureReconstructor(5, 10, false);
        assertEquals(0, noHeader.reconstruct(cells).header().length);
    }

    @Test
    void OCR多源框NMS去重与加权投票() {
        OcrBoxFuser fuser = new OcrBoxFuser(0.5, OcrBoxFuser.Strategy.WEIGHTED_VOTE);
        List<OcrBoxFuser.Box> boxes = List.of(
                new OcrBoxFuser.Box("总数：42台", 0.9, "engineA", 100, 100, 200, 30),
                new OcrBoxFuser.Box("总数：42台", 0.8, "engineB", 101, 100, 200, 30),
                new OcrBoxFuser.Box("总数：24台", 0.6, "engineC", 100, 100, 200, 30),
                new OcrBoxFuser.Box("页脚备注", 0.5, "engineA", 100, 700, 200, 20));
        List<OcrBoxFuser.FusedBox> fused = fuser.fuse(boxes);
        // 同位簇（前三个）+ 独立页脚 = 2 框
        assertEquals(2, fused.size());
        // 胜者按 NMS 置信度
        assertEquals("engineA", fused.get(0).source());
        // 加权投票：42台（0.9+0.8）胜 24台（0.6）
        assertEquals("总数：42台", fused.get(0).text());
        assertEquals(3, fused.get(0).contributors().size());
        // IoU 边界：完全不交IoU=0
        assertEquals(0.0, OcrBoxFuser.iou(boxes.get(0), boxes.get(3)));
    }
}
