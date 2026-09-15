package cn.chyuan.ai.config;

import io.milvus.grpc.GetCollectionStatisticsResponse;
import io.milvus.grpc.KeyValuePair;
import io.milvus.param.R;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MilvusCollectionHealthIndicator 三态转换测试（SELFLOOP6 loop-602，工单 0800/0801）。
 * R 响应包装静态构造，不 mock SDK client。
 */
class MilvusCollectionHealthIndicatorTest {

    private static GetCollectionStatisticsResponse statsResponse(String rowCount) {
        return GetCollectionStatisticsResponse.newBuilder()
                .addAllStats(List.of(KeyValuePair.newBuilder()
                        .setKey("num_entities").setValue(rowCount).build()))
                .build();
    }

    @Test
    @DisplayName("集合存在且统计成功 → UP 附行数")
    void upWithRowCount() {
        var health = MilvusCollectionHealthIndicator.toHealth("rag_docs",
                R.success(statsResponse("12345")));
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("rowCount", "12345")
                .containsEntry("collection", "rag_docs");
    }

    @Test
    @DisplayName("统计 RPC 失败 → DOWN 附原因")
    void downOnRpcFailure() {
        var health = MilvusCollectionHealthIndicator.toHealth("rag_docs",
                R.failed(R.Status.ConnectFailed, "connection refused"));
        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("reason", "统计查询失败");
    }

    @Test
    @DisplayName("无 num_entities 键 → UP 但行数 unknown（不误报 DOWN）")
    void upWithUnknownRowCount() {
        var health = MilvusCollectionHealthIndicator.toHealth("rag_docs",
                R.success(GetCollectionStatisticsResponse.newBuilder().build()));
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("rowCount", "unknown");
    }
}
