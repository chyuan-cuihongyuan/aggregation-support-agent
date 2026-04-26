package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.rag.service.IRagService;
import cn.chyuan.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查控制器 — 检查 Milvus 向量数据库连接状态和各服务可用性
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*")
public class HealthCheckController {

    @Resource
    private IRagService ragService;

    /**
     * Milvus 健康检查 — 检查向量数据库连接是否正常
     */
    @RequestMapping(value = "milvus/health", method = RequestMethod.GET)
    public Response<Map<String, Object>> milvusHealthCheck() {
        try {
            boolean healthy = ragService.healthCheck();

            Map<String, Object> data = new HashMap<>();
            data.put("milvus", healthy ? "UP" : "DOWN");
            data.put("rag", healthy ? "AVAILABLE" : "UNAVAILABLE");

            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(data)
                    .build();

        } catch (Exception e) {
            log.error("Milvus 健康检查失败", e);
            Map<String, Object> data = new HashMap<>();
            data.put("milvus", "DOWN");
            data.put("rag", "UNAVAILABLE");
            data.put("error", e.getMessage());

            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("Milvus 健康检查失败: " + e.getMessage())
                    .data(data)
                    .build();
        }
    }

}
