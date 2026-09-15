package cn.chyuan.ai.domain.docintel.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 表格结构重组（工单 0389 AV3，PaddleOCR PP-Structure 思想）。
 * 单元格框 → Y/X 区间聚类成行/列 → 网格定位（宽跨多列的合并格占连续列）；表头识别（首行启发，可关）；
 * 缺格补空。纯函数。
 */
public class TableStructureReconstructor {

    /** 单元格 */
    public record Cell(String id, double x, double y, double width, double height, String text) {
    }

    /** 表格结构：表头行 + 数据行 + 规模 */
    public record Structure(String[] header, String[][] rows, int rowCount, int colCount,
                            List<String[]> mergedSpans) {
    }

    private final double rowGap;
    private final double colGap;
    private final boolean headerFromFirstRow;

    public TableStructureReconstructor(double rowGap, double colGap, boolean headerFromFirstRow) {
        if (rowGap < 0 || colGap < 0) {
            throw new IllegalArgumentException("聚类间距阈值不可为负");
        }
        this.rowGap = rowGap;
        this.colGap = colGap;
        this.headerFromFirstRow = headerFromFirstRow;
    }

    /**
     * 重组：行按 Y 聚类、列按 X 聚类；单元格宽跨 ≥1.8 列宽占连续列；缺格补空串。
     */
    public Structure reconstruct(List<Cell> cells) {
        if (cells == null || cells.isEmpty()) {
            return new Structure(new String[0], new String[0][], 0, 0, List.of());
        }
        // 行聚类（按 Y 排序，间距 < rowGap 同行——取行内最大 Y 端延伸）
        List<List<Cell>> rowGroups = new ArrayList<>();
        List<Cell> sortedCells = new ArrayList<>(cells);
        sortedCells.sort(Comparator.comparingDouble(Cell::y));
        double rowEnd = Double.NEGATIVE_INFINITY;
        for (Cell cell : sortedCells) {
            if (rowGroups.isEmpty() || cell.y() - rowEnd >= rowGap) {
                rowGroups.add(new ArrayList<>());
            }
            rowGroups.get(rowGroups.size() - 1).add(cell);
            rowEnd = Math.max(rowEnd, cell.y() + cell.height());
        }
        // 列参考：全部单元格 X 区间聚类为列段（取每行内列中心对齐）
        List<double[]> colSegments = columnSegments(cells);
        int colCount = colSegments.size();
        List<String[]> rows = new ArrayList<>();
        List<String[]> merged = new ArrayList<>();
        for (List<Cell> row : rowGroups) {
            String[] grid = new String[colCount];
            java.util.Arrays.fill(grid, "");
            row.sort(Comparator.comparingDouble(Cell::x));
            for (Cell cell : row) {
                int startCol = nearestSegment(colSegments, cell.x());
                // 合并格：宽 ≥ 1.8 × 目标列宽 → 占连续列
                int span = 1;
                if (colCount > 1) {
                    double colWidth = colSegments.get(startCol)[1] - colSegments.get(startCol)[0];
                    if (cell.width() >= colWidth * 1.8) {
                        span = Math.max(1, (int) Math.round(cell.width() / colWidth));
                    }
                }
                int endCol = Math.min(colCount - 1, startCol + span - 1);
                for (int c = startCol; c <= endCol; c++) {
                    grid[c] = cell.text();
                }
                if (span > 1) {
                    merged.add(new String[]{cell.id(), String.valueOf(startCol), String.valueOf(endCol)});
                }
            }
            rows.add(grid);
        }
        // 表头：首行（可关）
        String[] header = headerFromFirstRow && !rows.isEmpty() ? rows.get(0) : new String[0];
        List<String[]> dataRows = headerFromFirstRow && !rows.isEmpty()
                ? rows.subList(1, rows.size()) : rows;
        return new Structure(header, dataRows.toArray(new String[0][]), rows.size(), colCount, merged);
    }

    /** 全部单元格 X 区间聚类为列段 [start,end] */
    private List<double[]> columnSegments(List<Cell> cells) {
        List<double[]> segments = new ArrayList<>();
        List<Cell> sorted = new ArrayList<>(cells);
        sorted.sort(Comparator.comparingDouble(Cell::x));
        for (Cell cell : sorted) {
            double start = cell.x();
            double end = cell.x() + cell.width();
            if (segments.isEmpty() || start - segments.get(segments.size() - 1)[1] >= colGap) {
                segments.add(new double[]{start, end});
            } else {
                double[] last = segments.get(segments.size() - 1);
                last[1] = Math.max(last[1], end);
            }
        }
        return segments;
    }

    private int nearestSegment(List<double[]> segments, double x) {
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < segments.size(); i++) {
            double dist = x < segments.get(i)[0] ? segments.get(i)[0] - x
                    : x > segments.get(i)[1] ? x - segments.get(i)[1] : 0;
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }
}
