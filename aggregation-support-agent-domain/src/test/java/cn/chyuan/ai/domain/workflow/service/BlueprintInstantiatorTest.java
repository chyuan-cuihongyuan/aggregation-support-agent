package cn.chyuan.ai.domain.workflow.service;

import cn.chyuan.ai.domain.workflow.service.BlueprintInstantiator.Instantiated;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 蓝图实例化器单测（工单 0273 AI6）：占位替换/参数缺失/类型不匹配/未知占位/产物可过校验。
 */
class BlueprintInstantiatorTest {

    private static final String TEMPLATE = """
            {"schemaVersion":1,"name":"tpl-${env}","nodes":[
              {"id":"fetch","type":"TASK","config":{"source":"${source}","retries":"3"}},
              {"id":"sum","type":"TASK","config":{"flag":"${debug}"}}],
             "edges":[{"from":"fetch","to":"sum"}]}
            """;

    @Test
    void 占位替换与替换点报告() {
        Instantiated result = BlueprintInstantiator.instantiate(TEMPLATE,
                Map.of("env", "prod", "source", "mysql", "debug", "true"),
                "{\"env\":\"string\",\"source\":\"string\",\"debug\":\"boolean\"}");
        // 仅节点 config 内占位替换（图名占位不处理）
        assertTrue(result.graphJson().contains("mysql"));
        assertTrue(!result.graphJson().contains("${source}"));
        assertEquals(2, result.replacements().size());
        assertTrue(cn.chyuan.ai.domain.workflow.service.GraphValidator.validate(result.graph()).valid());
    }

    @Test
    void 参数缺失与类型不匹配与未知占位() {
        // 缺参数（debug 未给值）
        Map<String, String> missing = new HashMap<>(Map.of("env", "prod", "source", "mysql"));
        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
                () -> BlueprintInstantiator.instantiate(TEMPLATE, missing, null));
        assertTrue(e1.getMessage().contains("缺少参数值: debug"));
        // 类型不匹配
        Map<String, String> badType = new HashMap<>(Map.of("env", "prod", "source", "mysql", "debug", "yes"));
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> BlueprintInstantiator.instantiate(TEMPLATE, badType,
                        "{\"env\":\"string\",\"source\":\"string\",\"debug\":\"boolean\"}"));
        assertTrue(e2.getMessage().contains("需为 boolean"));
        // 未声明占位（模板用了 debug 但 schema 未声明）
        IllegalArgumentException e3 = assertThrows(IllegalArgumentException.class,
                () -> BlueprintInstantiator.instantiate(TEMPLATE,
                        Map.of("env", "prod", "source", "mysql", "debug", "true"),
                        "{\"env\":\"string\",\"source\":\"string\"}"));
        assertTrue(e3.getMessage().contains("未在 schema 声明"));
        // 非法 schema 类型
        assertThrows(IllegalArgumentException.class,
                () -> BlueprintInstantiator.instantiate(TEMPLATE, Map.of(),
                        "{\"p\":\"bogus\"}"));
    }

    @Test
    void 数字类型校验与无占位模板() {
        String numeric = """
                {"schemaVersion":1,"name":"n","nodes":[
                  {"id":"a","type":"TASK","config":{"topK":"${k}"}}],"edges":[]}
                """;
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> BlueprintInstantiator.instantiate(numeric, Map.of("k", "ten"),
                        "{\"k\":\"number\"}"));
        assertTrue(e.getMessage().contains("需为 number"));
        Instantiated ok = BlueprintInstantiator.instantiate(numeric, Map.of("k", "10"),
                "{\"k\":\"number\"}");
        assertTrue(ok.graphJson().contains("\"topK\":\"10\""));
        // 无占位模板（无 schema）直接实例化
        String plain = """
                {"schemaVersion":1,"name":"p","nodes":[{"id":"a","type":"TASK","config":{}}],"edges":[]}
                """;
        Instantiated noPlaceholder = BlueprintInstantiator.instantiate(plain, Map.of(), null);
        assertEquals(0, noPlaceholder.replacements().size());
    }
}
