package cn.chyuan.ai.domain.workflow.model;

import cn.chyuan.ai.domain.workflow.model.GraphDiffCalculator.GraphDiff;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图版本 diff 单测（工单 0275 AI8）：节点四分类/边增删/参数变更明细。
 */
class GraphDiffCalculatorTest {

    private WorkflowGraph graph(String name, Map<String, String> aConfig) {
        return WorkflowGraph.builder(name)
                .node("a", WorkflowGraph.TYPE_TASK, aConfig)
                .node("b", WorkflowGraph.TYPE_TASK, Map.of())
                .node("c", WorkflowGraph.TYPE_TASK, Map.of())
                .edge("a", "b")
                .edge("b", "c")
                .build();
    }

    @Test
    void 节点四分类与边增删() {
        WorkflowGraph before = graph("g1", Map.of("k", "1"));
        WorkflowGraph after = WorkflowGraph.builder("g1")
                .node("a", WorkflowGraph.TYPE_TASK, Map.of("k", "2"))
                .node("b", WorkflowGraph.TYPE_TASK, Map.of())
                .node("d", WorkflowGraph.TYPE_TASK, Map.of())
                .edge("a", "b")
                .edge("b", "d")
                .build();
        GraphDiff diff = GraphDiffCalculator.diff(before, after);
        assertEquals(1, diff.added());
        assertEquals(1, diff.removed());
        assertEquals(1, diff.changed());
        assertEquals(1, diff.unchanged());
        assertTrue(diff.nodes().stream().anyMatch(n -> "a".equals(n.id())
                && GraphDiffCalculator.CHANGED.equals(n.change())
                && n.configBefore().containsKey("k")));
        assertTrue(diff.nodes().stream().anyMatch(n -> "d".equals(n.id())
                && GraphDiffCalculator.ADDED.equals(n.change())));
        assertTrue(diff.nodes().stream().anyMatch(n -> "c".equals(n.id())
                && GraphDiffCalculator.REMOVED.equals(n.change())));
        assertTrue(diff.edges().stream().anyMatch(e -> "b".equals(e.from()) && "d".equals(e.to())
                && GraphDiffCalculator.ADDED.equals(e.change())));
        assertTrue(diff.edges().stream().anyMatch(e -> "b".equals(e.from()) && "c".equals(e.to())
                && GraphDiffCalculator.REMOVED.equals(e.change())));
        assertFalse(diff.isEmpty());
    }

    @Test
    void 类型变化计入CHANGED与空diff() {
        WorkflowGraph before = WorkflowGraph.builder("g")
                .node("a", WorkflowGraph.TYPE_TASK, Map.of())
                .build();
        WorkflowGraph after = WorkflowGraph.builder("g")
                .node("a", WorkflowGraph.TYPE_INTERRUPT, Map.of())
                .build();
        GraphDiff diff = GraphDiffCalculator.diff(before, after);
        assertEquals(1, diff.changed());
        assertTrue(diff.nodes().get(0).configBefore().containsKey("type"));
        // 全同 → 空 diff
        assertTrue(GraphDiffCalculator.diff(before, before).isEmpty());
    }
}
