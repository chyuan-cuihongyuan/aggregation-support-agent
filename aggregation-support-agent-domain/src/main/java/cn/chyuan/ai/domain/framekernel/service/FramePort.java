package cn.chyuan.ai.domain.framekernel.service;

import java.util.List;
import java.util.Map;

/**
 * 数据框端口（工单 0877 CY8，polars 思想）。
 * fromRows·filter·aggregate 入口统一编排/与 querykernel 行集作建表输入形态只读联动（泛型 Map 行不 import）/
 * frame-kernel.enabled 默认关（开启才改变行为）。
 */
public interface FramePort {

    DataFrame fromRows(List<Map<String, Object>> rows);

    DataFrame filter(DataFrame df, List<DataFrame.Condition> conditions);

    DataFrame groupBy(DataFrame df, List<String> keys, List<GroupJoin.Agg> aggs);

    /** 行集列求和便捷聚合（与 CY3 聚合同口径，空值跳过） */
    Object aggregate(DataFrame df, String column, GroupJoin.Func func);

    static FramePort inMemory() {
        return new InMemoryFrame();
    }
}

final class InMemoryFrame implements FramePort {

    @Override
    public DataFrame fromRows(List<Map<String, Object>> rows) {
        return DataFrame.fromRows(rows);
    }

    @Override
    public DataFrame filter(DataFrame df, List<DataFrame.Condition> conditions) {
        return df.filter(conditions);
    }

    @Override
    public DataFrame groupBy(DataFrame df, List<String> keys, List<GroupJoin.Agg> aggs) {
        return GroupJoin.groupBy(df, keys, aggs);
    }

    @Override
    public Object aggregate(DataFrame df, String column, GroupJoin.Func func) {
        DataFrame agg = GroupJoin.groupBy(df, List.of(), List.of(new GroupJoin.Agg(column, func)));
        return agg.rows() == 0 ? null : agg.cell(column + "_" + func.name().toLowerCase(), 0);
    }
}
