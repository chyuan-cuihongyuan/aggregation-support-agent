package cn.chyuan.ai.domain.workflow.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流图定义值对象（工单 0204 AB1，借鉴 LangGraph StateGraph）—
 * 节点 + 有向边；节点类型含业务节点与两类控制节点（INTERRUPT 人工中断 / SUBGRAPH 子图引用）。
 * domain 纯内核：零框架依赖，非法构造立即失败。
 *
 * @author chyuan
 */
public record WorkflowGraph(String name, List<NodeSpec> nodes, List<EdgeSpec> edges) {

    /** 节点类型：普通业务节点 */
    public static final String TYPE_TASK = "TASK";
    /** 节点类型：人工中断（执行到此处挂起，等 resume 注入输入，工单 0207 AB4） */
    public static final String TYPE_INTERRUPT = "INTERRUPT";
    /** 节点类型：子图引用（展开后执行，工单 0208 AB5） */
    public static final String TYPE_SUBGRAPH = "SUBGRAPH";

    public WorkflowGraph {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("工作流名不能为空");
        }
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    /** 节点规格：id 唯一；config 不可变（键保序便于 DSL 往返） */
    public record NodeSpec(String id, String type, Map<String, String> config) {
        public NodeSpec {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("节点 id 不能为空");
            }
            type = type == null || type.isBlank() ? TYPE_TASK : type;
            config = config == null ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(config));
        }
    }

    /** 有向边 from → to */
    public record EdgeSpec(String from, String to) {
        public EdgeSpec {
            if (from == null || from.isBlank() || to == null || to.isBlank()) {
                throw new IllegalArgumentException("边端点不能为空");
            }
        }
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** 构造器（链式） */
    public static class Builder {
        private final String name;
        private final List<NodeSpec> nodes = new ArrayList<>();
        private final List<EdgeSpec> edges = new ArrayList<>();
        private final Map<String, NodeSpec> index = new LinkedHashMap<>();

        public Builder(String name) {
            this.name = name;
        }

        public Builder node(String id, String type, Map<String, String> config) {
            NodeSpec spec = new NodeSpec(id, type, config);
            if (index.containsKey(id)) {
                throw new IllegalArgumentException("节点 id 重复: " + id);
            }
            nodes.add(spec);
            index.put(id, spec);
            return this;
        }

        public Builder edge(String from, String to) {
            edges.add(new EdgeSpec(from, to));
            return this;
        }

        public WorkflowGraph build() {
            WorkflowGraph graph = new WorkflowGraph(name, nodes, edges);
            for (EdgeSpec edge : edges) {
                if (!index.containsKey(edge.from()) || !index.containsKey(edge.to())) {
                    throw new IllegalArgumentException("边引用不存在的节点: " + edge);
                }
            }
            return graph;
        }
    }
}
