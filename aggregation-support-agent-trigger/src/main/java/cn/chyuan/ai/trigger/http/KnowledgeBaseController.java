package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.dto.KnowledgeBaseCreateRequestDTO;
import cn.chyuan.ai.api.dto.KnowledgeBaseDTO;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.knowledgebase.adapter.repository.IKnowledgeBaseRepository;
import cn.chyuan.ai.domain.knowledgebase.model.entity.KnowledgeBaseEntity;
import cn.chyuan.ai.trigger.support.TenantScopeSupport;
import cn.chyuan.ai.types.enums.ResponseCode;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class KnowledgeBaseController {

    @Resource
    private IKnowledgeBaseRepository knowledgeBaseRepository;

    @GetMapping
    public Response<List<KnowledgeBaseDTO>> list(HttpServletRequest request) {
        try {
            TenantScopeVO scope = TenantScopeSupport.currentScope(request);
            List<KnowledgeBaseDTO> data = knowledgeBaseRepository.queryByScope(scope).stream()
                    .map(this::toDTO)
                    .collect(Collectors.toList());
            return Response.<List<KnowledgeBaseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(data)
                    .build();
        } catch (Exception e) {
            log.error("查询知识库列表失败", e);
            return Response.<List<KnowledgeBaseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("查询失败: " + e.getMessage())
                    .build();
        }
    }

    @PostMapping
    public Response<KnowledgeBaseDTO> create(HttpServletRequest request,
                                             @RequestBody KnowledgeBaseCreateRequestDTO body) {
        try {
            String name = body != null && body.getName() != null ? body.getName().trim() : "";
            if (name.isEmpty()) {
                return Response.<KnowledgeBaseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("知识库名称不能为空")
                        .build();
            }

            TenantScopeVO scope = TenantScopeSupport.currentScope(request);
            String knowledgeBaseId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            KnowledgeBaseEntity entity = KnowledgeBaseEntity.builder()
                    .knowledgeBaseId(knowledgeBaseId)
                    .tenantId(scope.getTenantId())
                    .ownerUserId(scope.getOwnerUserId())
                    .name(name)
                    .description(body.getDescription())
                    .icon(body.getIcon())
                    .color(body.getColor())
                    .deletedFlag(0)
                    .build();
            knowledgeBaseRepository.save(entity);

            KnowledgeBaseEntity saved = knowledgeBaseRepository.queryById(knowledgeBaseId, scope);
            return Response.<KnowledgeBaseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(toDTO(saved != null ? saved : entity))
                    .build();
        } catch (Exception e) {
            log.error("创建知识库失败", e);
            return Response.<KnowledgeBaseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("创建失败: " + e.getMessage())
                    .build();
        }
    }

    @DeleteMapping("/{knowledgeBaseId}")
    public Response<Void> delete(HttpServletRequest request,
                                 @PathVariable String knowledgeBaseId) {
        try {
            TenantScopeVO scope = TenantScopeSupport.currentScope(request);
            KnowledgeBaseEntity entity = knowledgeBaseRepository.queryById(knowledgeBaseId, scope);
            if (entity == null) {
                return Response.<Void>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("知识库不存在")
                        .build();
            }
            knowledgeBaseRepository.markDeleted(knowledgeBaseId, scope);
            return Response.<Void>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("删除知识库失败: {}", knowledgeBaseId, e);
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("删除失败: " + e.getMessage())
                    .build();
        }
    }

    private KnowledgeBaseDTO toDTO(KnowledgeBaseEntity entity) {
        KnowledgeBaseDTO dto = new KnowledgeBaseDTO();
        dto.setKnowledgeBaseId(entity.getKnowledgeBaseId());
        dto.setTenantId(entity.getTenantId());
        dto.setOwnerUserId(entity.getOwnerUserId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setIcon(entity.getIcon());
        dto.setColor(entity.getColor());
        dto.setDocumentCount(entity.getDocumentCount() != null ? entity.getDocumentCount() : 0L);
        dto.setCreateTime(entity.getCreateTime());
        dto.setUpdateTime(entity.getUpdateTime());
        return dto;
    }
}
