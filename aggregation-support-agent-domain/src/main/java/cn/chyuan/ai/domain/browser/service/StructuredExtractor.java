package cn.chyuan.ai.domain.browser.service;

import cn.chyuan.ai.domain.browser.model.valobj.ElementVO;
import cn.chyuan.ai.domain.browser.model.valobj.ExtractionResultVO;
import cn.chyuan.ai.domain.browser.model.valobj.PageSnapshotVO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构化抽取器（工单 0343 AQ5，browser-use structured extraction 思想）。
 * 抽取 schema（字段名/类型 text|number|link/多值标记/必填）→ 从快照按
 * role+name 约束提取元素文本 → 结果校验（类型匹配/必填/多值上限）。
 * domain 纯函数。
 */
public class StructuredExtractor {

    private static final int MAX_MULTI = 20;

    /** 抽取：字段按约束匹配（元素 role+name 大小写折叠包含） */
    public ExtractionResultVO extract(PageSnapshotVO snapshot,
                                      List<ExtractionResultVO.FieldSpec> schema) {
        if (schema == null || schema.isEmpty()) {
            throw new IllegalArgumentException("抽取 schema 不能为空");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (ExtractionResultVO.FieldSpec field : schema) {
            List<ElementVO> matched = match(snapshot, field.name());
            if (matched.isEmpty()) {
                if (field.required()) {
                    missing.add(field.name() + ":无匹配元素");
                }
                continue;
            }
            List<Object> collected = new ArrayList<>();
            for (ElementVO element : matched) {
                collected.add(convert(element.getName(), field.type()));
            }
            if (field.multiValue()) {
                if (collected.size() > MAX_MULTI) {
                    collected = collected.subList(0, MAX_MULTI);
                }
                values.put(field.name(), collected);
            } else {
                Object single = collected.get(0);
                if (!typeMatches(single, field.type())) {
                    missing.add(field.name() + ":类型不符");
                    continue;
                }
                values.put(field.name(), single);
            }
        }
        return ExtractionResultVO.builder()
                .success(missing.isEmpty() && !values.isEmpty())
                .values(values)
                .missing(missing)
                .build();
    }

    private List<ElementVO> match(PageSnapshotVO snapshot, String nameConstraint) {
        String needle = nameConstraint.toLowerCase();
        List<ElementVO> out = new ArrayList<>();
        for (ElementVO element : snapshot.getElements()) {
            if (element.getName() != null && element.getName().toLowerCase().contains(needle)) {
                out.add(element);
            }
        }
        return out;
    }

    private Object convert(String raw, String type) {
        if ("number".equals(type)) {
            try {
                return Long.parseLong(raw.replaceAll("[^0-9-]", ""));
            } catch (NumberFormatException e) {
                return raw;
            }
        }
        return raw;
    }

    private boolean typeMatches(Object value, String type) {
        if ("number".equals(type)) {
            return value instanceof Number;
        }
        if ("link".equals(type)) {
            return value instanceof String;
        }
        return value instanceof String;
    }
}
