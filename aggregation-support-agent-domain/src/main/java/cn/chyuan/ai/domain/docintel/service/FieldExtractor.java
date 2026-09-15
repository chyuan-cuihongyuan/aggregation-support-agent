package cn.chyuan.ai.domain.docintel.service;

import cn.chyuan.ai.domain.docintel.model.valobj.LayoutBlockVO;

import java.util.ArrayList;
import java.util.List;

/**
 * 字段抽取 schema 定位（工单 0393 AV7，unstructured 分区抽取思想）。
 * 抽取 schema（字段/锚点关键词/值类型/必填）× 版面叶元素 → 锚点命中定位 → 右侧/下方就近取值 →
 * 类型校验（NUMBER/DATE/ENUM/ANY）→ 三态结果（HIT/MISS/TYPE_MISMATCH）留痕。纯函数。
 */
public class FieldExtractor {

    /** 值类型 */
    public static final String NUMBER = "NUMBER";
    public static final String DATE = "DATE";
    public static final String ENUM = "ENUM";
    public static final String ANY = "ANY";

    /** 抽取字段 schema */
    public record FieldSchema(String name, String anchorKeyword, String type, List<String> allowedValues, boolean required) {
    }

    /** 抽取结果三态 */
    public record Extraction(String name, String state, String value, String fromBlockId, String detail) {
        public static final String HIT = "HIT";
        public static final String MISS = "MISS";
        public static final String TYPE_MISMATCH = "TYPE_MISMATCH";
    }

    /** 就近取值方向 */
    public enum Direction {
        RIGHT, BELOW
    }

    private final Direction direction;

    public FieldExtractor(Direction direction) {
        this.direction = direction == null ? Direction.RIGHT : direction;
    }

    /**
     * 抽取：每字段独立定位（首个锚点命中的块），取值方向按配置。
     */
    public List<Extraction> extract(List<LayoutBlockVO> leaves, List<FieldSchema> schemas) {
        List<Extraction> out = new ArrayList<>();
        for (FieldSchema schema : schemas == null ? List.<FieldSchema>of() : schemas) {
            out.add(extractOne(leaves, schema));
        }
        return out;
    }

    private Extraction extractOne(List<LayoutBlockVO> leaves, FieldSchema schema) {
        if (leaves == null || leaves.isEmpty() || schema == null) {
            return new Extraction(schema == null ? "" : schema.name(), Extraction.MISS, null, null, "无版面或空 schema");
        }
        int anchorIndex = -1;
        for (int i = 0; i < leaves.size(); i++) {
            if (leaves.get(i).getText() != null && leaves.get(i).getText().contains(schema.anchorKeyword())) {
                anchorIndex = i;
                break;
            }
        }
        if (anchorIndex < 0) {
            return new Extraction(schema.name(), Extraction.MISS, null, null,
                    "锚点未命中: " + schema.anchorKeyword());
        }
        LayoutBlockVO anchor = leaves.get(anchorIndex);
        String text = anchor.getText();
        int anchorPos = text.indexOf(schema.anchorKeyword());
        String value = null;
        String fromId = anchor.getId();
        if (direction == Direction.RIGHT) {
            value = text.substring(anchorPos + schema.anchorKeyword().length()).strip()
                    .replaceFirst("^[：:、,，\\s]+", "");
        } else if (anchorIndex + 1 < leaves.size()) {
            LayoutBlockVO below = leaves.get(anchorIndex + 1);
            value = below.getText();
            fromId = below.getId();
        } else {
            value = text.substring(anchorPos + schema.anchorKeyword().length()).strip();
        }
        value = value == null ? "" : value.strip();
        if (value.isEmpty()) {
            return new Extraction(schema.name(), Extraction.MISS, null, fromId, "取值为空");
        }
        String mismatch = validate(value, schema);
        if (mismatch != null) {
            return new Extraction(schema.name(), Extraction.TYPE_MISMATCH, value, fromId, mismatch);
        }
        return new Extraction(schema.name(), Extraction.HIT, value, fromId, null);
    }

    /** 类型校验：返回 null 合法，否则错误说明 */
    private String validate(String value, FieldSchema schema) {
        return switch (schema.type() == null ? ANY : schema.type()) {
            case NUMBER -> value.matches(".*[0-9].*") ? null : "值不含数字: " + value;
            case DATE -> value.matches(".*([0-9]{4}[-/年]|[0-9]{1,2}月).*") ? null : "非日期形态: " + value;
            case ENUM -> schema.allowedValues() != null && schema.allowedValues().contains(value) ? null
                    : "不在枚举域: " + value;
            default -> null;
        };
    }
}
