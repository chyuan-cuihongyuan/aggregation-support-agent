package cn.chyuan.ai.domain.docintel.service;

import cn.chyuan.ai.domain.docintel.model.valobj.LayoutBlockVO;

import java.util.ArrayList;
import java.util.List;

/**
 * 版面→Markdown 序列化（工单 0391 AV5，docling/marker 导出思想）。
 * 阅读顺序叶元素 → Markdown（标题层级映射/段落/表格管道语法/图注斜体）；管道符转义；确定性可重放。
 */
public class MarkdownSerializer {

    /** 标题层级基准（一级标题起始层级，默认 1 → #） */
    private final int baseLevel;

    public MarkdownSerializer(int baseLevel) {
        if (baseLevel < 1 || baseLevel > 5) {
            throw new IllegalArgumentException("基准层级须在 1-5");
        }
        this.baseLevel = baseLevel;
    }

    /**
     * 序列化：按阅读顺序叶元素清单渲染；TABLE 元素带 TableStructure 则渲染管道表。
     */
    public String serialize(List<LayoutBlockVO> ordered, java.util.Map<String, TableStructureReconstructor.Structure> tables) {
        StringBuilder sb = new StringBuilder();
        for (LayoutBlockVO block : ordered == null ? List.<LayoutBlockVO>of() : ordered) {
            String type = block.getType() == null ? LayoutBlockVO.PARAGRAPH : block.getType();
            switch (type) {
                case LayoutBlockVO.TITLE -> {
                    sb.append("#".repeat(baseLevel)).append(' ')
                            .append(escape(block.getText())).append("\n\n");
                }
                case LayoutBlockVO.TABLE -> {
                    TableStructureReconstructor.Structure structure = tables == null ? null : tables.get(block.getId());
                    sb.append(renderTable(structure)).append("\n\n");
                }
                case LayoutBlockVO.CAPTION -> sb.append('*').append(escape(block.getText())).append("*\n\n");
                default -> sb.append(escape(block.getText())).append("\n\n");
            }
        }
        return sb.toString().stripTrailing();
    }

    /** 管道表渲染（表头+分隔行+数据行） */
    private String renderTable(TableStructureReconstructor.Structure structure) {
        if (structure == null || (structure.header().length == 0 && structure.rows().length == 0)) {
            return "[空表]";
        }
        StringBuilder sb = new StringBuilder();
        int cols = structure.colCount();
        if (structure.header().length > 0) {
            sb.append('|');
            for (int c = 0; c < cols; c++) {
                sb.append(escape(headerAt(structure, c))).append('|');
            }
            sb.append('\n');
            sb.append('|').append(" --- |".repeat(cols)).append('\n');
        }
        for (String[] row : structure.rows()) {
            sb.append('|');
            for (int c = 0; c < cols; c++) {
                sb.append(escape(cellAt(row, c))).append('|');
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private String headerAt(TableStructureReconstructor.Structure structure, int col) {
        return col < structure.header().length ? structure.header()[col] : "";
    }

    private String cellAt(String[] row, int col) {
        return col < row.length ? row[col] : "";
    }

    /** 管道符转义 */
    private String escape(String text) {
        return text == null ? "" : text.replace("|", "\\|").replace("\n", " ");
    }
}
