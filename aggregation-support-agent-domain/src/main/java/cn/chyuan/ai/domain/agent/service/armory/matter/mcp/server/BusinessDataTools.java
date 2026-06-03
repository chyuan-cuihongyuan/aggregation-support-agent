package cn.chyuan.ai.domain.agent.service.armory.matter.mcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 业务数据查询工具 — 当知识库混合检索识别到需要Java业务数据时，通过此工具获取
 * <p>
 * 调用链路：Agent → MCP Gateway (对话理解+流程编排) → Java业务后端 → 返回结果
 * <p>
 * 支持的业务操作：订单查询、退款申请、开票管理
 */
@Slf4j
@Service
public class BusinessDataTools {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    @Value("${business.agent.base-url}")
    private String businessAgentBaseUrl;

    @Value("${business.agent.auth-key}")
    private String businessAgentAuthKey;

    @Value("${business.agent.enabled}")
    private boolean businessAgentEnabled;

    /**
     * 查询加油订单信息
     */
    @Tool(description = "查询加油订单信息，包括订单状态、金额、加油站点等。当用户询问订单相关问题时使用此工具。参数：orderNo-订单号（可选），phone-手机号（可选）")
    public String queryOrder(String orderNo, String phone) {
        if (!businessAgentEnabled) {
            return errorResult("业务数据服务未启用");
        }

        try {
            Map<String, Object> params = new HashMap<>();
            if (orderNo != null && !orderNo.isEmpty()) params.put("orderNo", orderNo);
            if (phone != null && !phone.isEmpty()) params.put("phone", phone);

            String result = postBusinessApi("/agent/api/order/query", params);
            log.info("订单查询完成: orderNo={}, phone={}", orderNo, phone);
            return result;
        } catch (Exception e) {
            log.error("订单查询失败: {}", e.getMessage());
            return errorResult("订单查询失败: " + e.getMessage());
        }
    }

    /**
     * 查询订单状态
     */
    @Tool(description = "查询加油订单的当前状态（支付成功/退款中等）。参数：orderNo-订单号")
    public String queryOrderStatus(String orderNo) {
        if (!businessAgentEnabled) {
            return errorResult("业务数据服务未启用");
        }

        try {
            Map<String, Object> params = new HashMap<>();
            params.put("orderNo", orderNo);

            return postBusinessApi("/agent/api/order/status", params);
        } catch (Exception e) {
            log.error("订单状态查询失败: {}", e.getMessage());
            return errorResult("订单状态查询失败: " + e.getMessage());
        }
    }

    /**
     * 申请退款
     */
    @Tool(description = "为加油订单申请退款。参数：orderNo-订单号，reason-退款原因")
    public String applyRefund(String orderNo, String reason) {
        if (!businessAgentEnabled) {
            return errorResult("业务数据服务未启用");
        }

        try {
            Map<String, Object> params = new HashMap<>();
            params.put("orderNo", orderNo);
            params.put("reason", reason);

            return postBusinessApi("/agent/api/order/refund/apply", params);
        } catch (Exception e) {
            log.error("退款申请失败: {}", e.getMessage());
            return errorResult("退款申请失败: " + e.getMessage());
        }
    }

    /**
     * 查询开票信息
     */
    @Tool(description = "查询加油订单的开票信息。参数：orderNo-订单号（可选），cpName-供应商名称（可选）")
    public String queryInvoice(String orderNo, String cpName) {
        if (!businessAgentEnabled) {
            return errorResult("业务数据服务未启用");
        }

        try {
            Map<String, Object> params = new HashMap<>();
            if (orderNo != null && !orderNo.isEmpty()) params.put("orderNo", orderNo);
            if (cpName != null && !cpName.isEmpty()) params.put("cpName", cpName);

            return postBusinessApi("/agent/api/invoice/query", params);
        } catch (Exception e) {
            log.error("开票信息查询失败: {}", e.getMessage());
            return errorResult("开票信息查询失败: " + e.getMessage());
        }
    }

    /**
     * 调用业务后端 API
     */
    private String postBusinessApi(String path, Map<String, Object> params) throws Exception {
        String url = businessAgentBaseUrl + path;
        String jsonBody = objectMapper.writeValueAsString(params);

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(jsonBody,
                        okhttp3.MediaType.parse("application/json; charset=utf-8")))
                .addHeader("X-Agent-Auth", businessAgentAuthKey)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.warn("业务API调用失败: url={}, status={}, body={}", url, response.code(), responseBody);
                // 根据HTTP状态码返回更友好的错误信息
                if (response.code() == 401) {
                    return errorResult("业务API认证失败，请检查配置");
                } else if (response.code() == 404) {
                    return errorResult("业务API接口不存在：" + path);
                } else if (response.code() >= 500) {
                    return errorResult("业务服务暂时不可用，请稍后重试");
                } else {
                    return errorResult("业务API返回错误: HTTP " + response.code());
                }
            }
            return responseBody;
        } catch (java.net.SocketTimeoutException e) {
            log.error("业务API调用超时: url={}", url, e);
            return errorResult("业务服务响应超时，请稍后重试");
        } catch (java.net.ConnectException e) {
            log.error("业务API连接失败: url={}", url, e);
            return errorResult("无法连接到业务服务，请检查服务状态");
        } catch (Exception e) {
            log.error("业务API调用异常: url={}, error={}", url, e.getMessage(), e);
            return errorResult("业务服务调用失败：" + e.getMessage());
        }
    }

    private String errorResult(String message) {
        try {
            Map<String, Object> error = new HashMap<>();
            error.put("error", true);
            error.put("message", message);
            return objectMapper.writeValueAsString(error);
        } catch (Exception e) {
            return "{\"error\":true,\"message\":\"" + message + "\"}";
        }
    }
}
