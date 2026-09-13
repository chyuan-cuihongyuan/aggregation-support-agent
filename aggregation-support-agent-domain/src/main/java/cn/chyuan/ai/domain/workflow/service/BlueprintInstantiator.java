package cn.chyuan.ai.domain.workflow.service;

import cn.chyuan.ai.domain.workflow.model.DslCodec;
import cn.chyuan.ai.domain.workflow.model.WorkflowGraph;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 蓝图实例化器（工单 0273 AI6，借鉴 Kestra 模板实例化）—
 * 模板图 JSON 中的 ${param} 占位按参数 schema 校验后替换，产出可注册的新图定义副本；
 * 未声明占位 / 参数缺失 / 类型不匹配均拒绝。纯函数：不自动注册（调用方拿产物走 register）。
 *
 * @author chyuan
 */
public final class BlueprintInstantiator {

    /** 占位符：${paramName} */
    public static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-zA-Z][a-zA-Z0-9_]*)}");

    /** 替换点记录 */
    public record Replacement(String nodeId, String configKey, String param, String value) {
    }

    /** 实例化产物 */
    public record Instantiated(String graphJson, WorkflowGraph graph, List<Replacement> replacements) {
    }

    private BlueprintInstantiator() {
    }

    /**
     * 实例化：模板 graphJson + params（字符串化值）→ 替换全部占位 → 图校验 → 产物。
     * paramSchemaJson 可空（空则仅做"未声明占位拒绝"与参数存在性校验）。
     */
    public static Instantiated instantiate(String graphJson, Map<String, String> params,
            String paramSchemaJson) {
        WorkflowGraph template = DslCodec.importDsl(graphJson);
        Map<String, String> schema = parseSchema(paramSchemaJson);
        Map<String, String> safeParams = params == null ? Map.of() : Map.copyOf(params);

        List<Replacement> replacements = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        // 收集模板实际使用的占位
        java.util.Set<String> used = new java.util.LinkedHashSet<>();
        for (WorkflowGraph.NodeSpec node : template.nodes()) {
            for (Map.Entry<String, String> entry : node.config().entrySet()) {
                Matcher matcher = PLACEHOLDER.matcher(entry.getValue());
                while (matcher.find()) {
                    used.add(matcher.group(1));
                }
            }
        }
        // 未声明的占位（schema 非空时校验声明集）+ 参数缺失
        for (String param : used) {
            if (!schema.isEmpty() && !schema.containsKey(param)) {
                errors.add("占位参数未在 schema 声明: " + param);
            }
            if (!safeParams.containsKey(param)) {
                errors.add("缺少参数值: " + param);
            }
        }

        // 类型校验（schema 声明 string/number/boolean）
        if (!schema.isEmpty()) {
            for (Map.Entry<String, String> entry : safeParams.entrySet()) {
                String type = schema.get(entry.getKey());
                if (type == null) {
                    continue;
                }
                String err = typeError(entry.getKey(), entry.getValue(), type);
                if (err != null) {
                    errors.add(err);
                }
            }
        }
        // 多余参数（schema 已声明但模板未用）不拒绝（宽松），仅严格拒绝未声明占位与缺失
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("蓝图实例化失败: " + String.join("; ", errors));
        }

        // 深拷贝替换
        JSONObject root = JSON.parseObject(DslCodec.export(template));
        JSONArray nodes = root.getJSONArray("nodes");
        for (int i = 0; i < nodes.size(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            String nodeId = node.getString("id");
            JSONObject config = node.getJSONObject("config");
            if (config == null) {
                continue;
            }
            for (Map.Entry<String, Object> entry : new java.util.LinkedHashMap<>(config).entrySet()) {
                Object value = entry.getValue();
                if (!(value instanceof String text)) {
                    continue;
                }
                Matcher matcher = PLACEHOLDER.matcher(text);
                if (!matcher.find()) {
                    continue;
                }
                String param = matcher.group(1);
                String replaced = matcher.replaceAll(
                        java.util.regex.Matcher.quoteReplacement(safeParams.getOrDefault(param, "")));
                config.put(entry.getKey(), replaced);
                replacements.add(new Replacement(nodeId, entry.getKey(), param, replaced));
            }
        }
        String outJson = root.toJSONString();
        WorkflowGraph graph = DslCodec.importDsl(outJson);
        return new Instantiated(outJson, graph, List.copyOf(replacements));
    }

    /** 参数 schema 解析：{"param":"number"} → 类型表（非法 schema 抛异常） */
    static Map<String, String> parseSchema(String paramSchemaJson) {
        if (paramSchemaJson == null || paramSchemaJson.isBlank()) {
            return Map.of();
        }
        try {
            JSONObject schema = JSON.parseObject(paramSchemaJson);
            Map<String, String> out = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : schema.entrySet()) {
                String type = String.valueOf(entry.getValue());
                if (!java.util.Set.of("string", "number", "boolean").contains(type)) {
                    throw new IllegalArgumentException("参数类型非法: " + entry.getKey() + "=" + type);
                }
                out.put(entry.getKey(), type);
            }
            return java.util.Collections.unmodifiableMap(out);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("参数 schema 非法 JSON");
        }
    }

    private static String typeError(String param, String value, String type) {
        switch (type) {
            case "number" -> {
                try {
                    Double.parseDouble(value);
                } catch (NumberFormatException e) {
                    return "参数 " + param + " 需为 number，实际: " + value;
                }
            }
            case "boolean" -> {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    return "参数 " + param + " 需为 boolean，实际: " + value;
                }
            }
            default -> {
                // string：任意非空即可
                if (value == null || value.isBlank()) {
                    return "参数 " + param + " 不能为空";
                }
            }
        }
        return null;
    }
}
