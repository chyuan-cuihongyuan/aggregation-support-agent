package cn.chyuan.ai.domain.workflow.service;

import cn.chyuan.ai.domain.workflow.model.WorkflowGraph;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子图展开单测（工单 0208 AB5）：命名空间/入口出口承接/递归防环/缺失引用。
 */
class GraphExpanderTest {

    @Test
    void 子图展开与命名空间() {
        WorkflowGraph sub = WorkflowGraph.builder("sub")
                .node("s1", "TASK", Map.of())
                .node("s2", "TASK", Map.of())
                .edge("s1", "s2").build();
        WorkflowGraph main = WorkflowGraph.builder("main")
                .node("in", "TASK", Map.of())
                .node("call", WorkflowGraph.TYPE_SUBGRAPH, Map.of("subgraph", "sub"))
                .node("out", "TASK", Map.of())
                .edge("in", "call").edge("call", "out").build();
        WorkflowGraph expanded = new GraphExpander(Map.of("sub", sub)).expand(main);
        assertTrue(GraphValidator.validate(expanded).valid());
        // SUBGRAPH 节点消失，实体节点带命名空间前缀
        assertTrue(expanded.nodes().stream().noneMatch(n -> n.id().equals("call")));
        assertTrue(expanded.nodes().stream().anyMatch(n -> n.id().equals("call.s1")));
        assertTrue(expanded.nodes().stream().anyMatch(n -> n.id().equals("call.s2")));
        // 入口承接：in → call.s1；出口承接：call.s2 → out
        assertTrue(expanded.edges().stream().anyMatch(e -> e.from().equals("in") && e.to().equals("call.s1")));
        assertTrue(expanded.edges().stream().anyMatch(e -> e.from().equals("call.s2") && e.to().equals("out")));
        assertEquals(4, expanded.nodes().size());
    }

    @Test
    void 递归自引用被拒() {
        WorkflowGraph recursive = WorkflowGraph.builder("self")
                .node("call", WorkflowGraph.TYPE_SUBGRAPH, Map.of("subgraph", "self"))
                .build();
        GraphExpander expander = new GraphExpander(Map.of("self", recursive));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> expander.expand(recursive));
        assertTrue(ex.getMessage().contains("深度"));
    }

    @Test
    void 互引用与缺失引用() {
        WorkflowGraph a = WorkflowGraph.builder("a")
                .node("callB", WorkflowGraph.TYPE_SUBGRAPH, Map.of("subgraph", "b")).build();
        WorkflowGraph b = WorkflowGraph.builder("b")
                .node("callA", WorkflowGraph.TYPE_SUBGRAPH, Map.of("subgraph", "a")).build();
        GraphExpander expander = new GraphExpander(Map.of("a", a, "b", b));
        assertThrows(IllegalArgumentException.class, () -> expander.expand(a));
        // 缺失引用
        WorkflowGraph dangling = WorkflowGraph.builder("dangle")
                .node("x", WorkflowGraph.TYPE_SUBGRAPH, Map.of("subgraph", "ghost")).build();
        assertThrows(IllegalArgumentException.class,
                () -> new GraphExpander(Map.of()).expand(dangling));
    }

    @Test
    void 无子图透传() {
        WorkflowGraph plain = WorkflowGraph.builder("plain")
                .node("a", "TASK", Map.of())
                .build();
        assertEquals(plain, new GraphExpander(Map.of()).expand(plain));
    }
}
