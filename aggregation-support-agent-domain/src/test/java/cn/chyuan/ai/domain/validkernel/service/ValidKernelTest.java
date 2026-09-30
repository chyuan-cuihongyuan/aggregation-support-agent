package cn.chyuan.ai.domain.validkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据校验内核测试（工单 1159-1166 FA1-FA8，pydantic 思想）。
 * 字段校验/宽松严格强转/约束/嵌套结构/默认值可选/自定义校验器/序列化/端口组合管线。
 */
class ValidKernelTest {

    @Test
    void fieldValidation() {
        ValidPort port = ValidPort.inMemory();
        port.field("name", "string");
        port.field("age", "int");
        assertTrue(port.validate(Map.of("name", "a", "age", 3)).isEmpty(), "类型匹配通过");
        assertEquals(1, port.validate(Map.of("name", "a", "age", "x")).size(), "类型不匹配失败");
        assertTrue(port.validate(Map.of("age", 3)).get(0).contains("name 必填缺失"), "必填缺失报字段");
        port.validate(Map.of("name", "a", "age", 3, "ghost", 1));
        assertEquals(List.of("ghost"), port.lastExtras(), "未知字段收集");
        assertThrows(IllegalArgumentException.class, () -> port.field("bad", "vector"), "未知类型声明拒绝");
    }

    @Test
    void coercions() {
        ValidPort lax = ValidPort.inMemory();
        lax.optional("count", "int");
        lax.optional("flag", "bool");
        lax.optional("tags", "list:int");
        Map<String, Object> instance = lax.parse(Map.of("count", "42", "flag", 1, "tags", List.of("1", 2)));
        assertEquals(42, instance.get("count"), "宽松模式字符串数字强转");
        assertEquals(Boolean.TRUE, instance.get("flag"), "0/1 布尔强转");
        assertEquals(List.of(1, 2), instance.get("tags"), "列表元素递归强转");

        ValidPort strict = ValidPort.inMemory();
        strict.strict();
        strict.optional("count", "int");
        assertEquals(1, strict.validate(Map.of("count", "42")).size(), "严格模式拒绝强转");
        assertThrows(IllegalArgumentException.class, () -> Coercions.coerce("abc", "int", false), "非数字无法强转");
        assertThrows(IllegalArgumentException.class, () -> Coercions.coerce("yes", "bool", false), "非法布尔串拒绝");
        assertEquals(Boolean.FALSE, Coercions.coerce("false", "bool", false));
        assertEquals(Boolean.FALSE, Coercions.coerce(0, "bool", false));
    }

    @Test
    void constraints() {
        ValidPort port = ValidPort.inMemory();
        port.field("age", "int").constrain("min", "18");
        port.field("score", "int").constrain("max", "100");
        port.field("code", "string").constrain("len", "2:4");
        port.field("mail", "string").constrain("pattern", "^[a-z]+@");
        port.field("level", "string").constrain("enum", "gold|silver");
        assertTrue(port.validate(Map.of("age", 20, "score", 90, "code", "abcd", "mail", "a@b", "level", "gold")).isEmpty());
        assertEquals(1, port.validate(Map.of("age", 17, "score", 90, "code", "abcd", "mail", "a@b", "level", "gold")).size());
        assertTrue(port.validate(Map.of("age", 20, "score", 101, "code", "abcd", "mail", "a@b", "level", "gold")).get(0).contains("score"), "违反约束报字段路径");
        assertEquals(1, port.validate(Map.of("age", 20, "score", 90, "code", "a", "mail", "a@b", "level", "gold")).size(), "长度下限");
        assertEquals(1, port.validate(Map.of("age", 20, "score", 90, "code", "abcd", "mail", "a@b", "level", "bronze")).size(), "枚举外拒绝");
        assertEquals(1, port.validate(Map.of("age", 20, "score", 90, "code", "abcd", "mail", "1@b", "level", "gold")).size(), "正则不匹配");
        assertThrows(IllegalArgumentException.class, () -> port.constrain("range", "1:2"), "未知约束种类拒绝");
    }

    @Test
    void nestedStructure() {
        ValidPort user = ValidPort.inMemory();
        user.field("name", "string");
        user.optional("age", "int");
        ValidPort port = ValidPort.inMemory();
        port.field("id", "int");
        port.field("user", "object").nested(user);
        port.field("scores", "list:int");
        assertTrue(port.validate(Map.of("id", 1, "user", Map.of("name", "a"), "scores", List.of(9, 8))).isEmpty());
        List<String> failures = port.validate(Map.of("id", 1, "user", Map.of("age", 3), "scores", List.of(9)));
        assertEquals(1, failures.size());
        assertEquals("user.name 必填缺失", failures.get(0), "嵌套错误路径点拼接");
        assertEquals(1, port.validate(Map.of("id", 1, "user", Map.of("name", "a"), "scores", List.of("x"))).size(), "列表元素类型失败");
        assertThrows(IllegalStateException.class,
                () -> port.parse(Map.of("id", 1, "user", "flat", "scores", List.of())), "嵌套类型不匹配 parse 抛出");
    }

    @Test
    void defaultsAndNullable() {
        ValidPort port = ValidPort.inMemory();
        port.field("name", "string");
        port.optional("region", "string").withDefault("cn");
        port.field("note", "string").nullable();
        Map<String, Object> withNull = new java.util.HashMap<>();
        withNull.put("name", "a");
        withNull.put("note", null);
        Map<String, Object> instance = port.parse(withNull);
        assertEquals("cn", instance.get("region"), "可选缺失默认值填充");
        Map<String, Object> explicit = port.parse(Map.of("name", "a", "region", "us", "note", "x"));
        assertEquals("us", explicit.get("region"), "显式值不覆盖");
        assertTrue(instance.containsKey("note") && instance.get("note") == null, "可空字段接受 null");
        assertThrows(IllegalStateException.class, () -> port.parse(Map.of("name", "a", "region", "us")), "必填缺失 parse 抛出");

        ValidPort noNull = ValidPort.inMemory();
        noNull.field("name", "string");
        assertEquals(1, noNull.validate(java.util.Collections.singletonMap("name", null)).size(), "不可空字段 null 拒绝");
    }

    @Test
    void customValidators() {
        CustomValidators validators = new CustomValidators();
        validators.addField("a", (field, value) -> (Integer) value > 0 ? null : "须为正数");
        validators.addField("a", (field, value) -> {
            throw new IllegalArgumentException("boom");
        });
        validators.addModel(instance -> (Integer) instance.get("a") > (Integer) instance.get("b") ? null : "a 须大于 b");
        List<String> failures = new java.util.ArrayList<>();
        validators.runField("a", -1, failures);
        validators.runModel(Map.of("a", -1, "b", 0), failures);
        assertEquals(3, failures.size(), "字段失败+校验器异常+模型跨字段失败");
        assertTrue(failures.get(0).contains("须为正数"));
        assertTrue(failures.get(1).contains("校验器异常"), "校验器异常转校验失败");
        assertTrue(failures.get(2).contains("a 须大于 b"));
        assertEquals(3, validators.trace().size(), "按注册序执行留痕");
        CustomValidators.FieldValidator noop = (field, value) -> null;
        assertThrows(IllegalArgumentException.class, () -> validators.addField(" ", noop), "空字段拒绝");

        ValidPort port = ValidPort.inMemory();
        port.field("a", "int");
        port.fieldValidator("a", (field, value) -> (Integer) value > 0 ? null : "须为正数");
        assertEquals(1, port.validate(Map.of("a", -1)).size(), "端口 validate 亦执行字段校验器");
        assertTrue(port.validate(Map.of("a", 1)).isEmpty());
    }

    @Test
    void serialization() {
        ValidPort port = ValidPort.inMemory();
        port.field("real_name", "string").alias("name");
        port.field("secret", "string").exclude();
        port.optional("age", "int");
        Map<String, Object> instance = port.parse(Map.of("real_name", "a", "secret", "s", "age", 3));
        Map<String, Object> dumped = port.dump(instance);
        assertEquals("a", dumped.get("name"), "别名导出");
        assertFalse(dumped.containsKey("secret"), "exclude 字段不导出");
        assertTrue(dumped.containsKey("age"), "实例显式字段导出");
        assertEquals(Map.of("name", "a", "age", 3), dumped, "roundtrip 导出与 schema 对齐");
    }

    @Test
    void portPipeline() {
        ValidPort port = ValidPort.inMemory();
        port.field("name", "string").constrain("len", "1:8");
        port.field("age", "int").constrain("min", "0");
        try {
            port.parse(Map.of("name", "", "age", -1));
            fail("聚合失败应抛出");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("长度不足") && e.getMessage().contains("低于下限"), "多失败聚合一条 ISE");
        }
        assertEquals(List.of(".", ".key", ".key[0]"), port.documentShape(), "jqkernel 文档形状只读联动");
    }
}
