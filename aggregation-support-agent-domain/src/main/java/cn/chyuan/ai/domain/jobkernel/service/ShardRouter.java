package cn.chyuan.ai.domain.jobkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 分片广播（工单 0499 BH4，xxl-job 分片广播思想）。
 * 总分片数 total/分片序 index 参数化/任务项按 index 取模路由/
 * 广播结果聚合含失败分片清单（分片序+原因）。
 */
public class ShardRouter {

    /** 分片参数 */
    public record ShardParam(int index, int total) {
        public ShardParam {
            if (total <= 0 || index < 0 || index >= total) {
                throw new IllegalArgumentException("分片参数非法: 须 0 ≤ index < total（index="
                        + index + ", total=" + total + "）");
            }
        }
    }

    /** 分片执行结果 */
    public record ShardResult(int index, boolean success, String reason) {
    }

    /** 广播聚合：全部分片结果 + 失败清单 */
    public record BroadcastResult(List<ShardResult> results, List<ShardResult> failures, boolean allSuccess) {
    }

    /** 任务项路由：按序号取模归属分片 */
    public static int routeItem(int itemIndex, int total) {
        if (total <= 0) {
            throw new IllegalArgumentException("total 须 > 0");
        }
        return itemIndex % total;
    }

    /** 本分片应处理的任务项序号列表 */
    public static List<Integer> itemsOfShard(int itemCount, ShardParam param) {
        List<Integer> items = new ArrayList<>();
        for (int i = 0; i < itemCount; i++) {
            if (routeItem(i, param.total()) == param.index()) {
                items.add(i);
            }
        }
        return items;
    }

    /** 广播结果聚合 */
    public static BroadcastResult aggregate(List<ShardResult> results) {
        List<ShardResult> failures = results.stream().filter(result -> !result.success()).toList();
        return new BroadcastResult(List.copyOf(results), List.copyOf(failures), failures.isEmpty());
    }
}
