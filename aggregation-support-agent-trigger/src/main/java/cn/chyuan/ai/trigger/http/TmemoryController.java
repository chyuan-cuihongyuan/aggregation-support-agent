package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.tmemory.model.valobj.EdgeVisibilityVO;
import cn.chyuan.ai.domain.tmemory.model.valobj.MemoryEdgeVO;
import cn.chyuan.ai.domain.tmemory.service.AsOfQueryEngine;
import cn.chyuan.ai.domain.tmemory.service.BiTemporalEdgeFactory;
import cn.chyuan.ai.domain.tmemory.service.HybridRetriever;
import cn.chyuan.ai.domain.tmemory.service.MemoryCompactor;
import cn.chyuan.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 时序知识记忆控制器（工单 0370 AS9）。
 * 端点清单（契约对账基线，见聚合 web tmemory-contract.test.ts）：
 * GET /api/v1/tmemory/edges、POST /api/v1/tmemory/edges、
 * GET /api/v1/tmemory/view、GET /api/v1/tmemory/search、GET /api/v1/tmemory/snapshot。
 * tmemory.enabled 默认关，开启才注册；记忆态为进程内演示实现（纯函数内核）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tmemory")
@ConditionalOnProperty(name = "tmemory.enabled", havingValue = "true")
public class TmemoryController {

    private final BiTemporalEdgeFactory factory = new BiTemporalEdgeFactory();
    private final List<MemoryEdgeVO> edges = new ArrayList<>();
    private final AsOfQueryEngine asOfEngine = new AsOfQueryEngine();
    private final MemoryCompactor compactor = new MemoryCompactor(1.0);

    /** 边清单（按事务序号） */
    @GetMapping("/edges")
    public Response<List<MemoryEdgeVO>> edges() {
        List<MemoryEdgeVO> sorted = new ArrayList<>(edges);
        sorted.sort(Comparator.comparingLong(MemoryEdgeVO::getIngestSeq));
        return Response.<List<MemoryEdgeVO>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(sorted)
                .build();
    }

    /** 新增事实边 */
    @PostMapping("/edges")
    public Response<MemoryEdgeVO> addEdge(@RequestParam String subject,
                                          @RequestParam String predicate,
                                          @RequestParam String object,
                                          @RequestParam long validFrom,
                                          @RequestParam(required = false) Long validTo,
                                          @RequestParam(defaultValue = "0.8") double confidence,
                                          @RequestParam(defaultValue = "unknown") String source,
                                          @RequestParam(defaultValue = "EPISODIC") String kind) {
        try {
            MemoryEdgeVO edge = factory.create(subject, predicate, object, validFrom, validTo, confidence, source, kind);
            edges.add(edge);
            return Response.<MemoryEdgeVO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info("记忆边已入库")
                    .data(edge)
                    .build();
        } catch (Exception e) {
            log.error("记忆边入库失败", e);
            return Response.<MemoryEdgeVO>builder().code(ResponseCode.UN_ERROR.getCode())
                    .info("记忆边入库失败: " + e.getMessage()).build();
        }
    }

    /** as-of 有效视图（逐边可见性解释） */
    @GetMapping("/view")
    public Response<List<EdgeVisibilityVO>> view(@RequestParam long asOf) {
        List<EdgeVisibilityVO> explained = asOfEngine.explainValid(edges, asOf);
        return Response.<List<EdgeVisibilityVO>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(explained)
                .build();
    }

    /** 混合检索（图邻域×关键词 RRF×时间衰减） */
    @GetMapping("/search")
    public Response<List<HybridRetriever.Retrieved>> search(@RequestParam(defaultValue = "") String q,
                                                            @RequestParam(defaultValue = "") String seeds) {
        Set<String> seedSet = new LinkedHashSet<>();
        for (String seed : seeds.split(",")) {
            if (!seed.isBlank()) {
                seedSet.add(seed.trim());
            }
        }
        List<String> queryTokens = q.isBlank() ? List.of() : List.of(q.split("[\\s,，]+"));
        HybridRetriever retriever = new HybridRetriever(2, 10, 86_400_000L);
        List<HybridRetriever.Retrieved> result = retriever.retrieve(edges, queryTokens, seedSet, System.currentTimeMillis());
        return Response.<List<HybridRetriever.Retrieved>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(result)
                .build();
    }

    /** 快照导出（确定性 JSON，活跃+归档） */
    @GetMapping("/snapshot")
    public Response<String> snapshot() {
        MemoryCompactor.Report report = compactor.compact(edges);
        String snapshot = compactor.snapshot(report, "tmemory-snapshot", System.currentTimeMillis());
        return Response.<String>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(snapshot)
                .build();
    }
}
