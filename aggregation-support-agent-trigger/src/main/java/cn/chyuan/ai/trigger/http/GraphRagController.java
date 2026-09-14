package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.adapter.repository.IGraphIndexRepository;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.CommunityPartitionVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphIndexVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphVisualizationVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.service.GraphVisualizationExporter;
import cn.chyuan.ai.types.enums.ResponseCode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GraphRAG 可视化控制器（工单 0314 AM9）。
 * 图 JSON 数据面（nodes/edges+社区着色+度数），前端零依赖渲染；
 * graphrag.enabled 默认关，开启才暴露端点。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/graphrag")
@ConditionalOnProperty(name = "graphrag.enabled", havingValue = "true")
public class GraphRagController {

    @Resource
    private IGraphIndexRepository graphIndexRepository;

    /** 可视化图 JSON 导出（度数优先采样） */
    @GetMapping("/visualization")
    public Response<GraphVisualizationVO> visualization(
            @RequestParam String indexId,
            @RequestParam(defaultValue = "200") int limit) {
        try {
            GraphIndexVO index = graphIndexRepository.findIndex(indexId);
            if (index == null) {
                return Response.<GraphVisualizationVO>builder()
                        .code(ResponseCode.UN_ERROR.getCode())
                        .info("图索引不存在: " + indexId)
                        .build();
            }
            CommunityPartitionVO partition = graphIndexRepository.findPartition(indexId);
            GraphVisualizationVO visualization = new GraphVisualizationExporter().export(index, partition, limit);
            return Response.<GraphVisualizationVO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(visualization)
                    .build();
        } catch (Exception e) {
            log.error("图谱可视化导出失败", e);
            return Response.<GraphVisualizationVO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("图谱可视化导出失败: " + e.getMessage())
                    .build();
        }
    }
}
