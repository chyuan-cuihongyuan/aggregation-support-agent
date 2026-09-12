package cn.chyuan.ai.domain.workflow.model;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作流 DSL 编解码（工单 0210 AB7，借鉴 Dify DSL）—
 * schemaVersion=1；导出 = 图定义 + 元信息（键序稳定）；导入 = 结构校验（版本/图合法性
 * 复用 GraphValidator）+ 反序列化。导出→导入→再导出字节一致（往返契约）。
 *
 * @author chyuan
 */
public final class DslCodec {

    /** 当前 DSL schema 版本 */
    public static final int SCHEMA_VERSION = 1;

    private DslCodec() {
    }

    /** 导出：稳定键序 JSON（LinkedHashMap 保序构造） */
    public static String export(WorkflowGraph graph) {
        JSONObject root = new JSONObject(true);
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("name", graph.name());
        JSONArray nodes = new JSONArray();
        for (WorkflowGraph.NodeSpec node : graph.nodes()) {
            JSONObject n = new JSONObject(true);
            n.put("id", node.id());
            n.put("type", node.type());
            JSONObject config = new JSONObject(true);
            for (Map.Entry<String, String> e : node.config().entrySet()) {
                config.put(e.getKey(), e.getValue());
            }
            n.put("config", config);
            nodes.add(n);
        }
        root.put("nodes", nodes);
        JSONArray edges = new JSONArray();
        for (WorkflowGraph.EdgeSpec edge : graph.edges()) {
            JSONObject e = new JSONObject(true);
            e.put("from", edge.from());
            e.put("to", edge.to());
            edges.add(e);
        }
        root.put("edges", edges);
        return root.toJSONString();
    }

    /** 导入：结构校验 + 反序列化（非法 DSL 抛 IllegalArgumentException） */
    public static WorkflowGraph importDsl(String dslJson) {
        JSONObject root;
        try {
            root = JSON.parseObject(dslJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("DSL 不是合法 JSON");
        }
        if (root == null) {
            throw new IllegalArgumentException("DSL 为空");
        }
        Integer version = root.getInteger("schemaVersion");
        if (version == null || version != SCHEMA_VERSION) {
            throw new IllegalArgumentException("不支持的 DSL schema 版本: " + version);
        }
        String name = root.getString("name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("DSL 缺少 name");
        }
        WorkflowGraph.Builder builder = WorkflowGraph.builder(name);
        JSONArray nodes = root.getJSONArray("nodes");
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("DSL 缺少 nodes");
        }
        for (int i = 0; i < nodes.size(); i++) {
            JSONObject n = nodes.getJSONObject(i);
            if (n.getString("id") == null) {
                throw new IllegalArgumentException("DSL 节点缺少 id（index=" + i + "）");
            }
            Map<String, String> config = new LinkedHashMap<>();
            JSONObject configJson = n.getJSONObject("config");
            if (configJson != null) {
                for (Map.Entry<String, Object> e : configJson.entrySet()) {
                    config.put(e.getKey(), String.valueOf(e.getValue()));
                }
            }
            builder.node(n.getString("id"), n.getString("type"), config);
        }
        JSONArray edges = root.getJSONArray("edges");
        if (edges != null) {
            for (int i = 0; i < edges.size(); i++) {
                JSONObject e = edges.getJSONObject(i);
                if (e.getString("from") == null || e.getString("to") == null) {
                    throw new IllegalArgumentException("DSL 边缺少端点（index=" + i + "）");
                }
                builder.edge(e.getString("from"), e.getString("to"));
            }
        }
        WorkflowGraph graph = builder.build();
        cn.chyuan.ai.domain.workflow.service.GraphValidator.ValidationResult validation =
                cn.chyuan.ai.domain.workflow.service.GraphValidator.validate(graph);
        if (!validation.valid()) {
            throw new IllegalArgumentException("DSL 图非法: " + String.join("; ", validation.errors()));
        }
        return graph;
    }

    /** 往返：导出→导入→再导出应字节一致 */
    public static boolean roundTripStable(WorkflowGraph graph) {
        return export(importDsl(export(graph))).equals(export(graph));
    }
}
