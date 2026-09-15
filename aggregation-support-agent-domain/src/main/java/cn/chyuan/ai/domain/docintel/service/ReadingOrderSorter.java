package cn.chyuan.ai.domain.docintel.service;

import cn.chyuan.ai.domain.docintel.model.valobj.LayoutBlockVO;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 阅读顺序排序（工单 0388 AV2）。
 * 版面叶元素 → 栏结构检测（X 区间聚类）→ 通栏元素优先（按 Y）→ 栏内按（栏序,Y,X）线性化；序号留痕。
 * 确定性：同输入同序列。纯函数。
 */
public class ReadingOrderSorter {

    /** 全栏阈值：宽度占页宽比例达到即视为通栏元素 */
    private final double fullWidthRatio;
    private final double pageWidth;
    private final double columnGap;

    public ReadingOrderSorter(double pageWidth, double fullWidthRatio, double columnGap) {
        if (pageWidth <= 0) {
            throw new IllegalArgumentException("页宽必须为正");
        }
        if (fullWidthRatio <= 0 || fullWidthRatio > 1) {
            throw new IllegalArgumentException("全栏阈值须在 (0,1]");
        }
        this.pageWidth = pageWidth;
        this.fullWidthRatio = fullWidthRatio;
        this.columnGap = Math.max(0, columnGap);
    }

    /**
     * 排序：返回带序号的元素列表（orderedIndex 字段借 LayoutBlockVO 副本 id 后缀无污染——用列表顺序表达序）。
     */
    public List<LayoutBlockVO> sort(List<LayoutBlockVO> leaves) {
        if (leaves == null || leaves.isEmpty()) {
            return List.of();
        }
        List<LayoutBlockVO> full = new ArrayList<>();
        List<LayoutBlockVO> columnar = new ArrayList<>();
        for (LayoutBlockVO block : leaves) {
            if (block.getWidth() >= pageWidth * fullWidthRatio) {
                full.add(block);
            } else {
                columnar.add(block);
            }
        }
        full.sort(Comparator.comparingDouble(LayoutBlockVO::getY).thenComparingDouble(LayoutBlockVO::getX));
        // 栏聚类：X 区间不重叠（间距>=columnGap 视为不同栏），按栏起点排序
        List<List<LayoutBlockVO>> columns = new ArrayList<>();
        columnar.sort(Comparator.comparingDouble(LayoutBlockVO::getX));
        for (LayoutBlockVO block : columnar) {
            boolean placed = false;
            for (List<LayoutBlockVO> column : columns) {
                double colStart = column.get(0).getX();
                double colEnd = columnEnd(column);
                // X 区间重叠（带间距容差）才归入同栏
                if (block.getX() < colEnd + columnGap && block.getX() + block.getWidth() > colStart - columnGap) {
                    column.add(block);
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                List<LayoutBlockVO> column = new ArrayList<>();
                column.add(block);
                columns.add(column);
            }
        }
        columns.forEach(c -> c.sort(Comparator.comparingDouble(LayoutBlockVO::getY)
                .thenComparingDouble(LayoutBlockVO::getX)));
        columns.sort(Comparator.comparingDouble(c -> c.get(0).getX()));
        // 装配：通栏在前（按 Y），其后逐栏
        List<LayoutBlockVO> out = new ArrayList<>(full);
        for (List<LayoutBlockVO> column : columns) {
            out.addAll(column);
        }
        return out;
    }

    private double columnEnd(List<LayoutBlockVO> column) {
        double end = 0;
        for (LayoutBlockVO block : column) {
            end = Math.max(end, block.getX() + block.getWidth());
        }
        return end;
    }
}
