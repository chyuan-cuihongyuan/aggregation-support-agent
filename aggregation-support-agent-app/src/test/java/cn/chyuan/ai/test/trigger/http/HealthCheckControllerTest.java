package cn.chyuan.ai.test.trigger.http;

import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.rag.service.IRagService;
import cn.chyuan.ai.trigger.http.HealthCheckController;
import cn.chyuan.ai.types.enums.ResponseCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 健康检查控制器单元测试
 * <p>
 * 测试场景：
 * 1. 基础健康检查（不依赖外部服务）
 * 2. Milvus 健康检查（正常状态）
 * 3. Milvus 健康检查（异常状态）
 * 4. Milvus 健康检查（RAG 服务未启用）
 * 5. Milvus 健康检查（连接异常）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("健康检查控制器测试")
public class HealthCheckControllerTest {

    @Mock
    private IRagService ragService;

    @InjectMocks
    private HealthCheckController healthCheckController;

    @Test
    @DisplayName("基础健康检查 — 返回 UP 状态和成功响应码")
    public void testHealthCheck_ReturnsUpStatus() {
        // 执行
        Response<Map<String, Object>> response = healthCheckController.healthCheck();

        // 验证
        assertNotNull(response, "响应不应为 null");
        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode(), "响应码应为 0000");
        assertEquals("服务运行正常", response.getInfo(), "响应信息应正确");
        assertNotNull(response.getData(), "响应数据不应为 null");
        assertEquals("UP", response.getData().get("status"), "服务状态应为 UP");
        assertNotNull(response.getData().get("timestamp"), "时间戳不应为 null");
    }

    @Test
    @DisplayName("基础健康检查 — 响应中包含时间戳且为数字类型")
    public void testHealthCheck_TimestampIsNumeric() {
        // 执行
        Response<Map<String, Object>> response = healthCheckController.healthCheck();

        // 验证
        Object timestamp = response.getData().get("timestamp");
        assertInstanceOf(Long.class, timestamp, "时间戳应为 Long 类型");
        assertTrue((Long) timestamp > 0, "时间戳应为正数");
    }

    @Test
    @DisplayName("Milvus 健康检查 — Milvus 连接正常时返回 UP")
    public void testMilvusHealthCheck_Healthy() {
        // 准备
        when(ragService.healthCheck()).thenReturn(true);

        // 执行
        Response<Map<String, Object>> response = healthCheckController.milvusHealthCheck();

        // 验证
        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode(), "响应码应为 0000");
        assertNotNull(response.getData(), "响应数据不应为 null");
        assertEquals("UP", response.getData().get("milvus"), "Milvus 状态应为 UP");
        assertEquals("AVAILABLE", response.getData().get("rag"), "RAG 应为 AVAILABLE");
        verify(ragService, times(1)).healthCheck();
    }

    @Test
    @DisplayName("Milvus 健康检查 — Milvus 连接异常时返回 DOWN")
    public void testMilvusHealthCheck_Unhealthy() {
        // 准备
        when(ragService.healthCheck()).thenReturn(false);

        // 执行
        Response<Map<String, Object>> response = healthCheckController.milvusHealthCheck();

        // 验证
        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode(), "响应码应为 0000");
        assertEquals("DOWN", response.getData().get("milvus"), "Milvus 状态应为 DOWN");
        assertEquals("UNAVAILABLE", response.getData().get("rag"), "RAG 应为 UNAVAILABLE");
    }

    @Test
    @DisplayName("Milvus 健康检查 — RAG 服务为 null 时返回 DISABLED 状态")
    public void testMilvusHealthCheck_RagServiceDisabled() {
        // 准备 — 使用新的 Controller 实例，ragService 为 null
        HealthCheckController controller = new HealthCheckController();

        // 执行
        Response<Map<String, Object>> response = controller.milvusHealthCheck();

        // 验证
        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode(), "响应码应为 0000");
        assertEquals("DISABLED", response.getData().get("milvus"), "Milvus 状态应为 DISABLED");
        assertEquals("UNAVAILABLE", response.getData().get("rag"), "RAG 应为 UNAVAILABLE");
        assertEquals("RAG服务未启用，请配置milvus.enabled=true", response.getData().get("message"),
                "提示信息应引导用户配置");
    }

    @Test
    @DisplayName("Milvus 健康检查 — 异常情况下返回错误响应码和 DOWN 状态")
    public void testMilvusHealthCheck_Exception() {
        // 准备
        when(ragService.healthCheck()).thenThrow(new RuntimeException("Milvus 连接超时"));

        // 执行
        Response<Map<String, Object>> response = healthCheckController.milvusHealthCheck();

        // 验证
        assertEquals(ResponseCode.UN_ERROR.getCode(), response.getCode(), "响应码应为未知失败");
        assertTrue(response.getInfo().contains("Milvus 健康检查失败"), "响应信息应包含失败描述");
        assertEquals("DOWN", response.getData().get("milvus"), "Milvus 状态应为 DOWN");
        assertEquals("UNAVAILABLE", response.getData().get("rag"), "RAG 应为 UNAVAILABLE");
        assertEquals("Milvus 连接超时", response.getData().get("error"), "错误信息应与异常消息一致");
    }

    @Test
    @DisplayName("基础健康检查 — 多次调用均返回成功")
    public void testHealthCheck_MultipleCalls_AlwaysSucceeds() {
        // 执行多次调用
        for (int i = 0; i < 5; i++) {
            Response<Map<String, Object>> response = healthCheckController.healthCheck();
            assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode(),
                    "第 " + (i + 1) + " 次调用响应码应为 0000");
        }
    }
}
