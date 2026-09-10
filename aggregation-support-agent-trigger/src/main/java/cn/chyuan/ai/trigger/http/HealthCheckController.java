package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.rag.adapter.repository.IVectorStoreRepository;
import cn.chyuan.ai.domain.rag.service.IRagService;
import cn.chyuan.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查控制器 — 检查向量引擎（pgvector 默认 / Milvus 过渡）连接状态和各服务可用性
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
public class HealthCheckController {

    @Autowired
    private IRagService ragService;

    /** 当前生效的向量仓储实现（端口注入；引擎由开关矩阵决定：pgvector 默认开、milvus 过渡保留） */
    @Autowired(required = false)
    private IVectorStoreRepository vectorStoreRepository;

    @Value("${pgvector.enabled:true}")
    private boolean pgvectorEnabled;

    @Value("${milvus.enabled:false}")
    private boolean milvusEnabled;

    /**
     * 基础健康检查 — 不依赖任何服务
     */
    @RequestMapping(value = "health", method = RequestMethod.GET)
    public Response<Map<String, Object>> healthCheck() {
        Map<String, Object> data = new HashMap<>();
        data.put("status", "UP");
        data.put("timestamp", System.currentTimeMillis());

        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info("服务运行正常")
                .data(data)
                .build();
    }

    /**
     * 向量引擎健康检查（三期 0131）— 返回当前引擎名（pgvector/milvus/none）与连接状态
     */
    @RequestMapping(value = "vector/health", method = RequestMethod.GET)
    public Response<Map<String, Object>> vectorHealthCheck() {
        Map<String, Object> data = new HashMap<>();
        String engine = currentEngine();

        if (vectorStoreRepository == null) {
            data.put("engine", engine);
            data.put("vector", "DISABLED");
            data.put("message", "向量引擎未装配（pgvector.enabled 与 milvus.enabled 均未开启）");
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(data)
                    .build();
        }

        try {
            boolean healthy = vectorStoreRepository.healthCheck();
            data.put("engine", engine);
            data.put("vector", healthy ? "UP" : "DOWN");
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(data)
                    .build();
        } catch (Exception e) {
            log.error("向量引擎健康检查失败（{}）", engine, e);
            data.put("engine", engine);
            data.put("vector", "DOWN");
            data.put("error", e.getMessage());
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("向量引擎健康检查失败: " + e.getMessage())
                    .data(data)
                    .build();
        }
    }

    /**
     * Milvus 健康检查（兼容旧路径）— 三期起语义为「当前向量引擎」健康检查
     */
    @RequestMapping(value = "milvus/health", method = RequestMethod.GET)
    public Response<Map<String, Object>> milvusHealthCheck() {
        Map<String, Object> data = new HashMap<>();

        if (ragService == null) {
            data.put("engine", currentEngine());
            data.put("milvus", "DISABLED");
            data.put("rag", "UNAVAILABLE");
            data.put("message", "RAG服务未启用（向量引擎默认 pgvector，Milvus 过渡需 milvus.enabled=true）");
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(data)
                    .build();
        }

        try {
            boolean healthy = ragService.healthCheck();
            data.put("engine", currentEngine());
            data.put("milvus", healthy ? "UP" : "DOWN");
            data.put("rag", healthy ? "AVAILABLE" : "UNAVAILABLE");
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(data)
                    .build();
        } catch (Exception e) {
            log.error("向量引擎健康检查失败", e);
            data.put("engine", currentEngine());
            data.put("milvus", "DOWN");
            data.put("rag", "UNAVAILABLE");
            data.put("error", e.getMessage());
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("向量引擎健康检查失败: " + e.getMessage())
                    .data(data)
                    .build();
        }
    }

    /** 引擎判定与装配矩阵一致：pgvector 优先，其次 milvus 过渡，均关为 none */
    private String currentEngine() {
        if (pgvectorEnabled) {
            return "pgvector";
        }
        if (milvusEnabled) {
            return "milvus";
        }
        return "none";
    }
}
