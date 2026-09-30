package cn.chyuan.ai.domain.validkernel.service;

import java.util.List;
import java.util.Map;

/**
 * 数据校验端口（工单 1166 FA8，pydantic 思想）。
 * parse·validate·dump 入口统一编排：字段类型·宽松严格强转·约束·嵌套结构·
 * 默认值可空·自定义校验器·序列化组合管线/jqkernel 文档形状只读联动
 * （形状键与 jq 过滤器路径形态对齐，不 import jqkernel）/
 * valid-kernel.enabled 默认关（开启才改变行为）。
 */
public interface ValidPort {

    /** 必填字段（FA1） */
    ValidPort field(String name, String type);

    /** 可选字段（FA5） */
    ValidPort optional(String name, String type);

    /** 修饰上一个字段：默认值（FA5） */
    ValidPort withDefault(Object value);

    /** 修饰上一个字段：可空（FA5） */
    ValidPort nullable();

    /** 修饰上一个字段：约束表达式 min=/max=/len=/pattern=/enum=（FA3） */
    ValidPort constrain(String kind, String expr);

    /** 修饰上一个字段：嵌套 Schema（FA4） */
    ValidPort nested(ValidPort nested);

    /** 修饰上一个字段：导出别名（FA7） */
    ValidPort alias(String alias);

    /** 修饰上一个字段：导出排除（FA7） */
    ValidPort exclude();

    /** 严格模式（默认宽松）（FA2） */
    ValidPort strict();

    /** 校验：返回失败列表，空即通过（FA1-FA5） */
    List<String> validate(Map<String, Object> document);

    /** 校验并产出实例（强转+默认填充+自定义校验器）；失败抛 ISE 聚合消息（FA2/FA5/FA6） */
    Map<String, Object> parse(Map<String, Object> document);

    /** 最近一次 validate/parse 收集到的未知字段（FA1） */
    List<String> lastExtras();

    /** 序列化导出（FA7） */
    Map<String, Object> dump(Map<String, Object> instance);

    /** 注册字段级校验器（FA6） */
    ValidPort fieldValidator(String field, CustomValidators.FieldValidator validator);

    /** 注册模型级跨字段校验器（FA6） */
    ValidPort modelValidator(CustomValidators.ModelValidator validator);

    /** jqkernel 文档形状只读联动（jq 路径形态） */
    List<String> documentShape();

    static ValidPort inMemory() {
        return new SchemaValidator();
    }
}
