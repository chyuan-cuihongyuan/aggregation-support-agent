package cn.chyuan.ai.domain.desiredkernel.service;

import java.util.List;
import java.util.Map;

/**
 * 配置模型（工单 0671 CB1，terraform HCL 思想）。
 * 块（类型/标签/属性表）与值类型（字符串/数字/布尔/列表/引用路径）。
 */
public final class ConfigModel {

    private ConfigModel() {
    }

    /** 值类型：字符串/数字/布尔/列表/引用（点分路径，如 server.db.host） */
    public sealed interface Value permits Str, Num, Bool, ListV, Ref {
    }

    public record Str(String s) implements Value {
    }

    public record Num(double d) implements Value {
    }

    public record Bool(boolean b) implements Value {
    }

    public record ListV(List<Value> items) implements Value {
    }

    public record Ref(List<String> path) implements Value {
    }

    /** 块：类型（resource/output/variable 等）+标签串+属性（保持源顺序） */
    public record Block(String type, List<String> labels, Map<String, Value> attrs) {
        public String identity() {
            return String.join(".", labels);
        }
    }

    public record Config(List<Block> blocks) {

        public List<Block> byType(String type) {
            return blocks.stream().filter(b -> b.type().equals(type)).toList();
        }
    }
}
