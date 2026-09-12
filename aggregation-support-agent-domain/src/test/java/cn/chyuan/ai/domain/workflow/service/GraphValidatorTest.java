package cn.chyuan.ai.domain.workflow.service;

import cn.chyuan.ai.domain.workflow.model.WorkflowGraph;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图校验单测（工单 0204 AB1）：DAG/环/孤立/终点/拓扑序/入度。
 */
class GraphValidatorTest {

    private static WorkflowGraph graph(WorkflowGraph.Builder builder) {
        return builder.build();
    }

    @Test
    void 合法线性图() {
        WorkflowGraph g = graph(WorkflowGraph.builder("lin")
                .node("a", "TASK", Map.of())
                .node("b", "TASK", Map.of())
                .edge("a", "b"));
        assertTrue(GraphValidator.validate(g).valid());
        assertEquals(List.of("a", "b"), GraphValidator.kahnTopoOrder(g, GraphValidator.indegreeOf(g)));
    }

    @Test
    void 合法分叉汇聚() {
        WorkflowGraph g = graph(WorkflowGraph.builder("diamond")
                .node("s", "TASK", Map.of())
                .node("l", "TASK", Map.of())
                .node("r", "TASK", Map.of())
                .node("t", "TASK", Map.of())
                .edge("s", "l").edge("s", "r").edge("l", "t").edge("r", "t"));
        assertTrue(GraphValidator.validate(g).valid());
        assertEquals(Map.of("s", 0, "l", 1, "r", 1, "t", 2), GraphValidator.indegreeOf(g));
        // 拓扑序：t 必在 l/r 之后
        List<String> order = GraphValidator.kahnTopoOrder(g, GraphValidator.indegreeOf(g));
        assertEquals(4, order.size());
        assertTrue(order.indexOf("t") > order.indexOf("l"));
        assertTrue(order.indexOf("t") > order.indexOf("r"));
    }

    @Test
    void 成环检测() {
        WorkflowGraph g = graph(WorkflowGraph.builder("cyc")
                .node("a", "TASK", Map.of())
                .node("b", "TASK", Map.of())
                .node("c", "TASK", Map.of())
                .edge("a", "b").edge("b", "c").edge("c", "b"));
        GraphValidator.ValidationResult result = GraphValidator.validate(g);
        assertFalse(result.valid());
        assertTrue(result.errors().get(0).contains("环"));
        // 环上节点不出现在拓扑序
        List<String> order = GraphValidator.kahnTopoOrder(g, GraphValidator.indegreeOf(g));
        assertEquals(List.of("a"), order);
    }

    @Test
    void 孤立子图与缺终点() {
        // 多起点链式合法（a→b 与 x 自成起点链）
        WorkflowGraph multiStart = graph(WorkflowGraph.builder("multi")
                .node("a", "TASK", Map.of())
                .node("b", "TASK", Map.of())
                .node("x", "TASK", Map.of())
                .edge("a", "b"));
        assertTrue(GraphValidator.validate(multiStart).valid());
        // 不可达：x 自环（入度>0 且无起点可达）
        WorkflowGraph isolated = graph(WorkflowGraph.builder("iso")
                .node("a", "TASK", Map.of())
                .node("b", "TASK", Map.of())
                .node("x", "TASK", Map.of())
                .edge("a", "b").edge("x", "x"));
        assertFalse(GraphValidator.validate(isolated).valid());
        assertTrue(GraphValidator.validate(isolated).errors().stream()
                .anyMatch(e -> e.contains("不可达")));
        // 缺终点：a↔b 互相成环且无出度 0 节点
        WorkflowGraph noExit = graph(WorkflowGraph.builder("noexit")
                .node("a", "TASK", Map.of())
                .node("b", "TASK", Map.of())
                .edge("a", "b").edge("b", "a"));
        assertTrue(GraphValidator.validate(noExit).errors().stream()
                .anyMatch(e -> e.contains("终点")));
    }

    @Test
    void 空图与单节点() {
        assertFalse(GraphValidator.validate(null).valid());
        WorkflowGraph empty = new WorkflowGraph("empty", List.of(), List.of());
        assertFalse(GraphValidator.validate(empty).valid());
        WorkflowGraph single = graph(WorkflowGraph.builder("solo").node("only", "TASK", Map.of()));
        assertTrue(GraphValidator.validate(single).valid());
    }

    @Test
    void 构造器防御() {
        // 节点 id 重复
        WorkflowGraph.Builder dup = WorkflowGraph.builder("dup");
        dup.node("a", "TASK", Map.of());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> dup.node("a", "TASK", Map.of()));
        // 边引用不存在节点
        WorkflowGraph.Builder dangling = WorkflowGraph.builder("dangling");
        dangling.node("a", "TASK", Map.of());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> dangling.edge("a", "ghost").build());
        // 空名/空端点
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new WorkflowGraph(" ", List.of(), List.of()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new WorkflowGraph.EdgeSpec("", "b"));
    }
}
