package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.rag.service.profile.ProfileComparator;
import cn.chyuan.ai.domain.rag.service.profile.RetrievalProfileCollector;
import cn.chyuan.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索参数画像端点（五期 AE8/AE9 0235-0236）—
 * POST /api/v1/rag/profile/record（记录一次检索样本）、GET /api/v1/rag/profile/stats（全配置统计）、
 * GET /api/v1/rag/profile/compare（两配置对比：各指标差值 + 优者标记，只观测不路由——AB 实验出界）。
 * 采集器为进程内环形聚合（每配置 200 样本），durable 落 retrieval_profile 表经端口后续接入。
 */
@Slf4j
@RestController
@CrossOrigin(origins = {"http://localhost:3000"})
@RequestMapping("/api/v1/rag/profile")
public class RagProfileController {

    private final RetrievalProfileCollector collector = new RetrievalProfileCollector();

    @PostMapping("/record")
    public Response<Map<String, Object>> record(@RequestParam int topK,
            @RequestParam int efSearch,
            @RequestParam(defaultValue = "0.5") double vectorRatio,
            @RequestParam int hitCount,
            @RequestParam long latencyMs) {
        RetrievalProfileCollector.ProfileKey key =
                new RetrievalProfileCollector.ProfileKey(topK, efSearch, vectorRatio);
        collector.record(key, new RetrievalProfileCollector.Sample(hitCount, topK, latencyMs));
        Map<String, Object> out = new HashMap<>();
        out.put("key", key.key());
        out.put("recorded", true);
        return ok(out);
    }

    @GetMapping("/stats")
    public Response<List<Map<String, Object>>> stats() {
        List<Map<String, Object>> stats = collector.allStats().stream()
                .map(RetrievalProfileCollector.ProfileStats::toMap).toList();
        Response<List<Map<String, Object>>> response = new Response<>();
        response.setCode(ResponseCode.SUCCESS.getCode());
        response.setInfo("成功");
        response.setData(stats);
        return response;
    }

    @GetMapping("/compare")
    public Response<Map<String, Object>> compare(@RequestParam int topKA,
            @RequestParam int efA, @RequestParam double vrA,
            @RequestParam int topKB, @RequestParam int efB, @RequestParam double vrB) {
        RetrievalProfileCollector.ProfileStats statsA =
                collector.stats(new RetrievalProfileCollector.ProfileKey(topKA, efA, vrA));
        RetrievalProfileCollector.ProfileStats statsB =
                collector.stats(new RetrievalProfileCollector.ProfileKey(topKB, efB, vrB));
        return ok(ProfileComparator.compare(statsA, statsB).toMap());
    }

    private static Response<Map<String, Object>> ok(Map<String, Object> data) {
        Response<Map<String, Object>> response = new Response<>();
        response.setCode(ResponseCode.SUCCESS.getCode());
        response.setInfo("成功");
        response.setData(data);
        return response;
    }
}
