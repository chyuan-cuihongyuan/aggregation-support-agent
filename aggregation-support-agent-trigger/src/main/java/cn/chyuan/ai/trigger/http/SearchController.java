package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.searchkernel.service.FacetAggregator;
import cn.chyuan.ai.domain.searchkernel.service.InMemoryInvertedIndex;
import cn.chyuan.ai.domain.searchkernel.service.SearchQueryParser;
import cn.chyuan.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 检索引擎控制器（工单 0404 AW9，typesense multi-search 接口面）。
 * 端点清单（契约基线，见 SearchEndpointContract）：
 * GET /api/v1/search/index、POST /api/v1/search/index、
 * DELETE /api/v1/search/index/{docId}、GET /api/v1/search/query。
 * searchkernel.enabled 默认关，开启才注册；索引态为进程内演示实现（纯函数内核）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/search")
@ConditionalOnProperty(name = "searchkernel.enabled", havingValue = "true")
public class SearchController {

    private final InMemoryInvertedIndex index = new InMemoryInvertedIndex();

    /** 索引文档清单（快照） */
    @GetMapping("/index")
    public Response<List<InMemoryInvertedIndex.Doc>> documents() {
        return Response.<List<InMemoryInvertedIndex.Doc>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(index.snapshot())
                .build();
    }

    /** 写入/更新文档（版本严格递增） */
    @PostMapping("/index")
    public Response<String> upsert(@RequestParam String docId,
                                   @RequestParam(defaultValue = "default") String indexName,
                                   @RequestParam String title,
                                   @RequestParam(defaultValue = "") String body,
                                   @RequestParam(defaultValue = "1") long version) {
        try {
            index.upsert(docId, title, body, Map.of("index", indexName), version);
            return Response.<String>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info("文档已索引")
                    .data(docId)
                    .build();
        } catch (InMemoryInvertedIndex.VersionConflictException e) {
            return Response.<String>builder().code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage()).build();
        }
    }

    /** 删除文档（墓碑） */
    @DeleteMapping("/index/{docId}")
    public Response<String> delete(@PathVariable String docId) {
        index.delete(docId);
        return Response.<String>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info("文档已删除")
                .data(docId)
                .build();
    }

    /** 检索（查询解析→倒排命中→facet 分布，multi-search 语义子集） */
    @GetMapping("/query")
    public Response<Map<String, Object>> query(@RequestParam(defaultValue = "") String q) {
        SearchQueryParser.Query parsed = new SearchQueryParser().parse(q);
        java.util.Set<String> hits = new java.util.LinkedHashSet<>();
        for (String term : parsed.terms()) {
            hits.addAll(index.posting(term));
        }
        List<InMemoryInvertedIndex.Doc> docs = index.activeDocs().stream()
                .filter(doc -> hits.contains(doc.getId()))
                .toList();
        FacetAggregator.FacetResult facets = new FacetAggregator().aggregate(
                docs.stream().map(doc -> Map.of("index", doc.getFields().getOrDefault("index", "default")))
                        .toList(),
                List.of("index"), Map.of());
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(Map.of("hits", docs.size(), "facets", facets.counts(), "warnings", parsed.warnings()))
                .build();
    }
}
