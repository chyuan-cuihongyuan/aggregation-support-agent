package cn.chyuan.ai.domain.framekernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据框内核测试（工单 0870-0877 CY1-CY8，polars 思想）。
 * 列组构建/谓词过滤/groupby 聚合/hash join/排序 top-k/惰性下推/投影重命名/端口行集联动。
 */
class FrameKernelTest {

    private static DataFrame sample() {
        return DataFrame.builder()
                .appendColumn("id", List.of(1L, 2L, 3L, 4L))
                .appendColumn("city", List.of("hz", "sh", "hz", "bj"))
                .appendColumn("score", java.util.Arrays.asList(90L, null, 75L, 88L))
                .build();
    }

    @Test
    void columnBuildAndConsistency() {
        DataFrame df = sample();
        assertEquals(4, df.rows());
        assertEquals(List.of("id", "city", "score"), df.columnNames());
        assertNull(df.cell("score", 1), "null 保位");
        assertThrows(IllegalArgumentException.class,
                () -> DataFrame.builder().appendColumn("a", List.of(1L)).appendColumn("b", List.of(1L, 2L)).build(),
                "行数不一致拒绝");
        assertThrows(IllegalArgumentException.class, () -> df.column("nope"), "未知列拒绝");
        DataFrame empty = DataFrame.fromRows(List.of());
        assertEquals(0, empty.rows());
    }

    @Test
    void predicateFilterNullSemantics() {
        DataFrame df = sample();
        DataFrame high = df.filter(List.of(
                new DataFrame.Condition("score", DataFrame.Condition.Op.GE, 80L)));
        assertEquals(2, high.rows(), "null 参与比较排除");
        assertEquals(1L, high.cell("id", 0));
        assertEquals(4L, high.cell("id", 1));
        DataFrame city = df.filter(List.of(
                new DataFrame.Condition("city", DataFrame.Condition.Op.EQ, "hz")));
        assertEquals(2, city.rows());
    }

    @Test
    void groupByAggregates() {
        DataFrame agg = GroupJoin.groupBy(sample(), List.of("city"),
                List.of(new GroupJoin.Agg("score", GroupJoin.Func.SUM),
                        new GroupJoin.Agg("id", GroupJoin.Func.COUNT)));
        assertEquals(3, agg.rows(), "hz/sh/bj 三组");
        assertEquals("score_sum", agg.columnNames().get(1));
        Map<Object, Object> byCity = new java.util.HashMap<>();
        for (int r = 0; r < agg.rows(); r++) {
            byCity.put(agg.cell("city", r), agg.cell("score_sum", r));
        }
        assertEquals(165L, byCity.get("hz"), "90+75 null 跳过");
        assertEquals(88L, byCity.get("bj"));
        assertNull(byCity.get("sh"), "全 null 组 SUM null");
        DataFrame counts = GroupJoin.groupBy(sample(), List.of("city"),
                List.of(new GroupJoin.Agg("score", GroupJoin.Func.COUNT)));
        Map<Object, Object> cnt = new java.util.HashMap<>();
        for (int r = 0; r < counts.rows(); r++) {
            cnt.put(counts.cell("city", r), counts.cell("score_count", r));
        }
        assertEquals(2L, cnt.get("hz"), "COUNT 计非空");
        assertEquals(0L, cnt.get("sh"));
    }

    @Test
    void hashJoinInner() {
        DataFrame left = DataFrame.builder()
                .appendColumn("uid", List.of(1L, 2L, 2L, 3L))
                .appendColumn("name", List.of("a", "b", "c", "d"))
                .build();
        DataFrame right = DataFrame.builder()
                .appendColumn("uid", List.of(2L, 3L, 2L))
                .appendColumn("role", List.of("admin", "user", "guest"))
                .build();
        DataFrame joined = GroupJoin.hashJoin(left, right, "uid");
        assertEquals(5, joined.rows(), "2×2 + 3×1 内连接");
        assertEquals(List.of("uid", "name", "role"), joined.columnNames(), "键列不重复");
        assertTrue(joined.cell("role", 0).equals("admin") || joined.cell("role", 0).equals("guest"),
                "重复键组合");
        DataFrame withNull = DataFrame.builder()
                .appendColumn("uid", java.util.Arrays.asList(3L, null))
                .appendColumn("n", List.of("x", "y"))
                .build();
        assertEquals(1, GroupJoin.hashJoin(withNull, right, "uid").rows(), "null 键不匹配");
    }

    @Test
    void sortStableAndTopK() {
        DataFrame df = DataFrame.builder()
                .appendColumn("name", List.of("a", "b", "c", "d"))
                .appendColumn("v", java.util.Arrays.asList(2L, 1L, 2L, null))
                .build();
        DataFrame sorted = df.sort(List.of(new DataFrame.SortKey("v", true), new DataFrame.SortKey("name", true)));
        assertEquals(List.of("b", "a", "c", "d"), sorted.column("name"), "升序稳定 null 殿后");
        DataFrame top2 = df.topK("v", 2, true);
        assertEquals(2, top2.rows());
        assertEquals(2L, top2.cell("v", 0));
        assertEquals(2L, top2.cell("v", 1));
    }

    @Test
    void lazyPushdownAndCollect() {
        DataFrame source = sample();
        LazyFrame lazy = LazyFrame.of(source)
                .project(List.of("id", "city", "score"))
                .filter(new DataFrame.Condition("score", DataFrame.Condition.Op.GE, 80L));
        assertTrue(lazy.explain().contains("filter"), "计划解释");
        assertEquals(1, lazy.pushdown(), "谓词下推交换");
        DataFrame collected = lazy.collect();
        assertEquals(2, collected.rows());
        assertEquals(List.of("id", "city", "score"), collected.columnNames());
    }

    @Test
    void lazyGroupByPlan() {
        LazyFrame lazy = LazyFrame.of(sample())
                .groupBy(List.of("city"), List.of(new GroupJoin.Agg("score", GroupJoin.Func.MAX)))
                .project(List.of("city", "score_max"));
        DataFrame result = lazy.collect();
        assertEquals(List.of("city", "score_max"), result.columnNames());
        assertEquals(3, result.rows());
    }

    @Test
    void projectRenameRejects() {
        DataFrame df = sample();
        DataFrame projected = df.project(List.of("city"));
        assertEquals(1, projected.columns());
        assertEquals(4, projected.rows(), "行数守恒");
        assertThrows(IllegalArgumentException.class, () -> df.project(List.of("nope")), "未知投影拒绝");
        DataFrame renamed = df.rename(Map.of("score", "points"));
        assertEquals(List.of("id", "city", "points"), renamed.columnNames());
        assertThrows(IllegalArgumentException.class, () -> df.rename(Map.of("id", "city")), "重命名冲突拒绝");
    }

    @Test
    void portOrchestrationAndRowLinkage() {
        FramePort port = FramePort.inMemory();
        DataFrame df = port.fromRows(List.of(
                Map.of("id", 1L, "city", "hz", "score", 90L),
                Map.of("id", 2L, "city", "sh"),
                Map.of("id", 3L, "city", "hz", "score", 75L)));
        assertEquals(3, df.rows(), "querykernel 行集形态建表只读联动");
        DataFrame high = port.filter(df, List.of(
                new DataFrame.Condition("score", DataFrame.Condition.Op.GE, 80L)));
        assertEquals(1, high.rows());
        assertEquals(165L, port.aggregate(df, "score", GroupJoin.Func.SUM));
        assertEquals(2L, port.aggregate(df, "score", GroupJoin.Func.COUNT));
    }
}
