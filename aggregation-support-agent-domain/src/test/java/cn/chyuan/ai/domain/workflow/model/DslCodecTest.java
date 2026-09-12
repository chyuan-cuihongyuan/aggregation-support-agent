package cn.chyuan.ai.domain.workflow.model;

import cn.chyuan.ai.domain.workflow.service.GraphValidator;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DSL 编解码单测（工单 0210 AB7）：往返一致/坏 DSL 拒绝集。
 */
class DslCodecTest {

    private static final WorkflowGraph GRAPH = WorkflowGraph.builder("order-flow")
            .node("start", "TASK", Map.of("param", "v1"))
            .node("gate", WorkflowGraph.TYPE_INTERRUPT, Map.of())
            .node("end", "TASK", Map.of())
            .edge("start", "gate").edge("gate", "end")
            .build();

    @Test
    void 导出导入往返一致() {
        String exported = DslCodec.export(GRAPH);
        assertTrue(exported.contains("\"schemaVersion\":1"));
        WorkflowGraph imported = DslCodec.importDsl(exported);
        assertEquals(GRAPH, imported);
        assertTrue(DslCodec.roundTripStable(GRAPH));
        // 再导出字节一致
        assertEquals(exported, DslCodec.export(imported));
    }

    @Test
    void 坏DSL拒绝集() {
        // 非 JSON
        assertThrows(IllegalArgumentException.class, () -> DslCodec.importDsl("{bad"));
        // 空对象
        assertThrows(IllegalArgumentException.class, () -> DslCodec.importDsl("{}"));
        // 版本不支持
        assertThrows(IllegalArgumentException.class, () -> DslCodec.importDsl(
                "{\"schemaVersion\":99,\"name\":\"x\",\"nodes\":[],\"edges\":[]}"));
        // 缺 name
        assertThrows(IllegalArgumentException.class, () -> DslCodec.importDsl(
                "{\"schemaVersion\":1,\"nodes\":[{\"id\":\"a\"}],\"edges\":[]}"));
        // 缺 nodes
        assertThrows(IllegalArgumentException.class, () -> DslCodec.importDsl(
                "{\"schemaVersion\":1,\"name\":\"x\"}"));
        // 节点缺 id
        assertThrows(IllegalArgumentException.class, () -> DslCodec.importDsl(
                "{\"schemaVersion\":1,\"name\":\"x\",\"nodes\":[{\"type\":\"TASK\"}],\"edges\":[]}"));
        // 边缺端点
        assertThrows(IllegalArgumentException.class, () -> DslCodec.importDsl(
                "{\"schemaVersion\":1,\"name\":\"x\",\"nodes\":[{\"id\":\"a\"}],"
                        + "\"edges\":[{\"from\":\"a\"}]}"));
        // 成环图
        assertThrows(IllegalArgumentException.class, () -> DslCodec.importDsl(
                "{\"schemaVersion\":1,\"name\":\"x\",\"nodes\":[{\"id\":\"a\"},{\"id\":\"b\"}],"
                        + "\"edges\":[{\"from\":\"a\",\"to\":\"b\"},{\"from\":\"b\",\"to\":\"a\"}]}"));
    }

    @Test
    void 导入图可校验可执行() {
        WorkflowGraph imported = DslCodec.importDsl(DslCodec.export(GRAPH));
        assertTrue(GraphValidator.validate(imported).valid());
        assertFalse(imported.nodes().isEmpty());
    }
}
