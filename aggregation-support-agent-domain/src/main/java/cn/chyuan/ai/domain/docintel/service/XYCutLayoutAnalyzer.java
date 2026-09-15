package cn.chyuan.ai.domain.docintel.service;

import cn.chyuan.ai.domain.docintel.model.valobj.LayoutBlockVO;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * XY-cut 递归版面分析（工单 0387 AV1，marker XY-cut 思想）。
 * 页面元素框 → 投影找最宽空白带递归 X/Y 切分 → 层次版面树（内部节点含切分轴，叶节点挂元素）。
 * 最小空白带阈值可配；空页/单元素边界。纯函数。
 */
public class XYCutLayoutAnalyzer {

    /** 版面树节点 */
    public static final class Node {
        /** 轴：ROOT/X/Y/LEAF */
        private final String axis;
        /** 叶元素（LEAF 时非空） */
        private final LayoutBlockVO block;
        /** 子节点（内部节点） */
        private final List<Node> children;

        private Node(String axis, LayoutBlockVO block, List<Node> children) {
            this.axis = axis;
            this.block = block;
            this.children = children;
        }

        static Node leaf(LayoutBlockVO block) {
            return new Node("LEAF", block, List.of());
        }

        static Node internal(String axis, List<Node> children) {
            return new Node(axis, null, children);
        }

        public String getAxis() {
            return axis;
        }

        public LayoutBlockVO getBlock() {
            return block;
        }

        public List<Node> getChildren() {
            return children;
        }

        public boolean isLeaf() {
            return "LEAF".equals(axis);
        }
    }

    private final double minGap;

    public XYCutLayoutAnalyzer(double minGap) {
        if (minGap < 0) {
            throw new IllegalArgumentException("最小空白带阈值不可为负");
        }
        this.minGap = minGap;
    }

    /**
     * 递归切分。
     */
    public Node analyze(List<LayoutBlockVO> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            throw new IllegalArgumentException("空页面不可分析");
        }
        return cut(sorted(blocks));
    }

    private Node cut(List<LayoutBlockVO> blocks) {
        if (blocks.size() == 1) {
            return Node.leaf(blocks.get(0));
        }
        // Y 轴投影：找水平空白带（按 y 区间分组）
        List<List<LayoutBlockVO>> yGroups = project(blocks, true);
        if (yGroups.size() > 1) {
            List<Node> children = new ArrayList<>();
            yGroups.forEach(group -> children.add(cut(sorted(group))));
            return Node.internal("Y", children);
        }
        // X 轴投影：找垂直空白带（分栏）
        List<List<LayoutBlockVO>> xGroups = project(blocks, false);
        if (xGroups.size() > 1) {
            List<Node> children = new ArrayList<>();
            xGroups.forEach(group -> children.add(cut(sorted(group))));
            return Node.internal("X", children);
        }
        // 无空白带可切：按面积排序逐叶返回（保底）
        List<Node> children = new ArrayList<>();
        for (LayoutBlockVO block : blocks) {
            children.add(Node.leaf(block));
        }
        return Node.internal("LEAF-BAG", children);
    }

    /**
     * 投影分组：vertical=true 按 y 区间聚类（水平空白带切行），false 按 x 区间聚类（垂直空白带切栏）。
     * 仅保留间距 >= minGap 的切分。
     */
    private List<List<LayoutBlockVO>> project(List<LayoutBlockVO> blocks, boolean vertical) {
        List<LayoutBlockVO> sorted = vertical
                ? sorted(blocks, Comparator.comparingDouble(LayoutBlockVO::getY))
                : sorted(blocks, Comparator.comparingDouble(LayoutBlockVO::getX));
        List<List<LayoutBlockVO>> groups = new ArrayList<>();
        List<LayoutBlockVO> current = new ArrayList<>();
        double currentEnd = Double.NEGATIVE_INFINITY;
        for (LayoutBlockVO block : sorted) {
            double start = vertical ? block.getY() : block.getX();
            double end = start + (vertical ? block.getHeight() : block.getWidth());
            if (current.isEmpty() || start - currentEnd >= minGap) {
                if (!current.isEmpty()) {
                    groups.add(current);
                }
                current = new ArrayList<>();
            }
            current.add(block);
            currentEnd = Math.max(currentEnd, end);
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    private List<LayoutBlockVO> sorted(List<LayoutBlockVO> blocks) {
        return sorted(blocks, Comparator.comparingDouble(LayoutBlockVO::getY)
                .thenComparingDouble(LayoutBlockVO::getX));
    }

    private List<LayoutBlockVO> sorted(List<LayoutBlockVO> blocks, Comparator<LayoutBlockVO> comparator) {
        List<LayoutBlockVO> out = new ArrayList<>(blocks);
        out.sort(comparator);
        return out;
    }
}
