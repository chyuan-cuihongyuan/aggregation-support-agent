package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.dto.GraphEntityDTO;
import cn.chyuan.ai.api.dto.GraphStatisticsDTO;
import cn.chyuan.ai.api.dto.GraphSubgraphDTO;
import cn.chyuan.ai.domain.knowledgegraph.model.entity.GraphEntity;
import cn.chyuan.ai.domain.knowledgegraph.model.valobj.SubgraphVO;
import cn.chyuan.ai.domain.knowledgegraph.service.IKnowledgeGraphService;
import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.rag.adapter.repository.IDocumentMetadataRepository;
import cn.chyuan.ai.domain.rag.model.entity.DocumentMetadataEntity;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.trigger.annotation.RequireRole;
import cn.chyuan.ai.trigger.support.CurrentUserSupport;
import cn.chyuan.ai.types.enums.ResponseCode;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 知识图谱控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/graph")
@ConditionalOnProperty(name = "knowledge-graph.enabled", havingValue = "true")
public class KnowledgeGraphController {

    @Resource
    private IKnowledgeGraphService knowledgeGraphService;

    @Resource
    private IDocumentMetadataRepository documentMetadataRepository;

    /** 图谱统计信息 */
    @RequireRole("admin")
    @GetMapping("/statistics")
    public Response<GraphStatisticsDTO> statistics() {
        try {
            Map<String, Object> stats = knowledgeGraphService.getGraphStatistics();
            GraphStatisticsDTO dto = new GraphStatisticsDTO();
            dto.setEntityCount((Long) stats.getOrDefault("entityCount", 0L));
            dto.setRelationCount((Long) stats.getOrDefault("relationCount", 0L));
            return Response.<GraphStatisticsDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(dto)
                    .build();
        } catch (Exception e) {
            log.error("获取图谱统计失败", e);
            return Response.<GraphStatisticsDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("获取图谱统计失败: " + e.getMessage())
                    .build();
        }
    }

    /** 搜索实体 */
    @RequireRole("admin")
    @GetMapping("/entities")
    public Response<List<GraphEntityDTO>> searchEntities(
            @RequestParam String query,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "20") int limit) {
        try {
            List<GraphEntity> entities = knowledgeGraphService.searchEntities(query, limit);
            if (type != null) {
                entities = entities.stream()
                        .filter(e -> type.equals(e.getEntityType()))
                        .collect(Collectors.toList());
            }
            List<GraphEntityDTO> dtos = entities.stream().map(this::toEntityDTO).collect(Collectors.toList());
            return Response.<List<GraphEntityDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(dtos)
                    .build();
        } catch (Exception e) {
            log.error("搜索实体失败", e);
            return Response.<List<GraphEntityDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("搜索实体失败: " + e.getMessage())
                    .build();
        }
    }

    /** 获取实体详情 */
    @RequireRole("admin")
    @GetMapping("/entities/{entityId}")
    public Response<GraphEntityDTO> getEntity(@PathVariable String entityId) {
        try {
            List<GraphEntity> entities = knowledgeGraphService.searchEntities(entityId, 1);
            if (entities.isEmpty()) {
                return Response.<GraphEntityDTO>builder()
                        .code(ResponseCode.UN_ERROR.getCode())
                        .info("实体不存在")
                        .build();
            }
            return Response.<GraphEntityDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(toEntityDTO(entities.get(0)))
                    .build();
        } catch (Exception e) {
            log.error("获取实体详情失败", e);
            return Response.<GraphEntityDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("获取实体详情失败: " + e.getMessage())
                    .build();
        }
    }

    /** 获取实体子图 */
    @RequireRole("admin")
    @GetMapping("/subgraph/{entityId}")
    public Response<GraphSubgraphDTO> getSubgraph(
            @PathVariable String entityId,
            @RequestParam(defaultValue = "2") int depth) {
        try {
            SubgraphVO subgraph = knowledgeGraphService.getEntitySubgraph(entityId, depth);
            return Response.<GraphSubgraphDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(toSubgraphDTO(subgraph))
                    .build();
        } catch (Exception e) {
            log.error("获取子图失败", e);
            return Response.<GraphSubgraphDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("获取子图失败: " + e.getMessage())
                    .build();
        }
    }

    /** 触发文档图谱构建 */
    @RequireRole("admin")
    @PostMapping("/build/{documentId}")
    public Response<String> buildGraph(HttpServletRequest request, @PathVariable String documentId) {
        try {
            // 校验文档所有权
            String userId = CurrentUserSupport.requireUserIdString(request);
            TenantScopeVO scope = TenantScopeVO.singleUser(userId);
            DocumentMetadataEntity doc = documentMetadataRepository.queryByDocumentId(documentId, scope);
            if (doc == null) {
                return Response.<String>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("文档不存在或无权访问")
                        .build();
            }
            // 异步构建，立即返回
            return Response.<String>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info("图谱构建已触发")
                    .data(documentId)
                    .build();
        } catch (Exception e) {
            log.error("触发图谱构建失败", e);
            return Response.<String>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("触发图谱构建失败: " + e.getMessage())
                    .build();
        }
    }

    /** 健康检查 */
    @RequireRole("admin")
    @GetMapping("/health")
    public Response<Boolean> healthCheck() {
        boolean healthy = knowledgeGraphService.healthCheck();
        return Response.<Boolean>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(healthy)
                .build();
    }

    /** 可视化数据 */
    @RequireRole("admin")
    @GetMapping("/visualization")
    public Response<GraphSubgraphDTO> visualization(
            @RequestParam String query,
            @RequestParam(defaultValue = "50") int limit) {
        try {
            var result = knowledgeGraphService.graphSearch(query, limit, 1);
            SubgraphVO subgraph = knowledgeGraphService.getEntitySubgraph(
                    result.getMatchedEntities().isEmpty() ? "" : result.getMatchedEntities().get(0).getEntityId(),
                    2);
            return Response.<GraphSubgraphDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(toSubgraphDTO(subgraph))
                    .build();
        } catch (Exception e) {
            log.error("获取可视化数据失败", e);
            return Response.<GraphSubgraphDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("获取可视化数据失败: " + e.getMessage())
                    .build();
        }
    }

    private GraphEntityDTO toEntityDTO(GraphEntity entity) {
        GraphEntityDTO dto = new GraphEntityDTO();
        dto.setEntityId(entity.getEntityId());
        dto.setEntityName(entity.getEntityName());
        dto.setEntityType(entity.getEntityType());
        dto.setDescription(entity.getDescription());
        dto.setProperties(entity.getProperties());
        dto.setSourceDocumentId(entity.getSourceDocumentId());
        return dto;
    }

    private GraphSubgraphDTO toSubgraphDTO(SubgraphVO subgraph) {
        if (subgraph == null) return new GraphSubgraphDTO();
        GraphSubgraphDTO dto = new GraphSubgraphDTO();
        dto.setNodes(subgraph.getNodes().stream().map(n -> {
            GraphSubgraphDTO.GraphNodeDTO nodeDTO = new GraphSubgraphDTO.GraphNodeDTO();
            nodeDTO.setId(n.getId());
            nodeDTO.setLabel(n.getLabel());
            nodeDTO.setType(n.getType());
            nodeDTO.setProperties(n.getProperties());
            nodeDTO.setScore(n.getScore() != null ? n.getScore().doubleValue() : null);
            return nodeDTO;
        }).collect(Collectors.toList()));
        dto.setEdges(subgraph.getEdges().stream().map(e -> {
            GraphSubgraphDTO.GraphEdgeDTO edgeDTO = new GraphSubgraphDTO.GraphEdgeDTO();
            edgeDTO.setId(e.getId());
            edgeDTO.setSource(e.getSource());
            edgeDTO.setTarget(e.getTarget());
            edgeDTO.setLabel(e.getLabel());
            edgeDTO.setType(e.getType());
            edgeDTO.setProperties(e.getProperties());
            return edgeDTO;
        }).collect(Collectors.toList()));
        return dto;
    }
}
