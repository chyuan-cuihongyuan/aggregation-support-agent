package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.dto.*;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.audit.service.IAuditLogService;
import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.rag.adapter.repository.IDocumentMetadataRepository;
import cn.chyuan.ai.domain.rag.model.entity.DocumentMetadataEntity;
import cn.chyuan.ai.domain.rag.model.valobj.SearchResultDetailVO;
import cn.chyuan.ai.domain.rag.service.IRagService;
import cn.chyuan.ai.trigger.filter.JwtAuthFilter;
import cn.chyuan.ai.trigger.support.AuditContextSupport;
import cn.chyuan.ai.trigger.support.TenantScopeSupport;
import cn.chyuan.ai.types.enums.AuditAction;
import cn.chyuan.ai.types.enums.AuditResult;
import cn.chyuan.ai.types.enums.ResponseCode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    @Autowired(required = false)
    private IRagService ragService;

    @Autowired
    private IDocumentMetadataRepository documentMetadataRepository;

    @Resource
    private IAuditLogService auditLogService;

    @GetMapping
    public Response<List<DocumentDTO>> listDocuments(HttpServletRequest request) {
        try {
            TenantScopeVO scope = currentScope(request);
            List<DocumentMetadataEntity> entities = documentMetadataRepository.queryByScope(scope);
            List<DocumentDTO> dtos = entities.stream().map(this::toDTO).collect(Collectors.toList());
            return Response.<List<DocumentDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(dtos)
                    .build();
        } catch (Exception e) {
            log.error("查询文档列表失败", e);
            return Response.<List<DocumentDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("查询失败: " + e.getMessage())
                    .build();
        }
    }

    @GetMapping("/{documentId}")
    public Response<DocumentDTO> getDocument(HttpServletRequest request, @PathVariable String documentId) {
        try {
            DocumentMetadataEntity entity = documentMetadataRepository.queryByDocumentId(documentId, currentScope(request));
            if (entity == null) {
                return Response.<DocumentDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("文档不存在")
                        .build();
            }
            return Response.<DocumentDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(toDTO(entity))
                    .build();
        } catch (Exception e) {
            log.error("查询文档详情失败: {}", documentId, e);
            return Response.<DocumentDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("查询失败: " + e.getMessage())
                    .build();
        }
    }

    @DeleteMapping("/{documentId}")
    public Response<Void> deleteDocument(HttpServletRequest request, @PathVariable String documentId) {
        String ip = AuditContextSupport.extractIp(request);
        String ua = AuditContextSupport.extractUserAgent(request);
        // 提取登录用户用于审计
        Object uidAttr = request.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        Object unameAttr = request.getAttribute(JwtAuthFilter.ATTR_USERNAME);
        Long auditUserId = (uidAttr instanceof Long) ? (Long) uidAttr : 0L;
        String auditUsername = (unameAttr instanceof String) ? (String) unameAttr : "";
        try {
            TenantScopeVO scope = currentScope(request);
            DocumentMetadataEntity entity = documentMetadataRepository.queryByDocumentId(documentId, scope);
            if (entity == null) {
                safeAuditDelete(auditUserId, auditUsername, documentId,
                        AuditResult.FAILURE, "文档不存在", ip, ua);
                return Response.<Void>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("文档不存在")
                        .build();
            }
            if (ragService != null) {
                ragService.deleteDocument(documentId, scope);
            } else {
                documentMetadataRepository.markDeletedByDocumentId(documentId, scope);
            }
            log.info("文档已软删除: documentId={}, userId={}", documentId, entity.getUserId());
            // 删除成功审计
            safeAuditDelete(auditUserId, auditUsername, documentId,
                    AuditResult.SUCCESS, "", ip, ua);
            return Response.<Void>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("删除文档失败: {}", documentId, e);
            safeAuditDelete(auditUserId, auditUsername, documentId,
                    AuditResult.FAILURE, "删除失败: " + e.getMessage(), ip, ua);
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("删除失败: " + e.getMessage())
                    .build();
        }
    }

    @PostMapping("/search")
    public Response<SearchTestResultDTO> searchTest(HttpServletRequest servletRequest,
                                                    @RequestBody SearchTestRequestDTO request) {
        try {
            if (ragService == null) {
                return Response.<SearchTestResultDTO>builder()
                        .code(ResponseCode.UN_ERROR.getCode())
                        .info("RAG服务未启用")
                        .build();
            }

            SearchResultDetailVO detail = ragService.searchWithDetails(
                    request.getQuery(),
                    request.getTopK(),
                    currentScope(servletRequest)
            );

            SearchTestResultDTO dto = new SearchTestResultDTO();
            dto.setQuery(detail.getQuery());
            dto.setVectorResults(convertItems(detail.getVectorResults()));
            dto.setBm25Results(convertItems(detail.getBm25Results()));
            dto.setHybridResults(convertItems(detail.getHybridResults()));

            return Response.<SearchTestResultDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(dto)
                    .build();
        } catch (Exception e) {
            log.error("检索测试失败", e);
            return Response.<SearchTestResultDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("检索失败: " + e.getMessage())
                    .build();
        }
    }

    private DocumentDTO toDTO(DocumentMetadataEntity entity) {
        DocumentDTO dto = new DocumentDTO();
        dto.setDocumentId(entity.getDocumentId());
        dto.setTenantId(entity.getTenantId());
        dto.setOwnerUserId(entity.getOwnerUserId());
        dto.setVisibility(entity.getVisibility());
        dto.setFileName(entity.getFileName());
        dto.setFileExtension(entity.getFileExtension());
        dto.setFileSize(entity.getFileSize());
        dto.setMimeType(entity.getMimeType());
        dto.setTotalChars(entity.getTotalChars());
        dto.setTotalChunks(entity.getTotalChunks());
        dto.setSectionCount(entity.getSectionCount());
        dto.setProcessingStatus(entity.getProcessingStatus());
        dto.setErrorMessage(entity.getErrorMessage());
        dto.setUserId(entity.getUserId());
        dto.setCreateTime(entity.getCreateTime());
        dto.setUpdateTime(entity.getUpdateTime());
        return dto;
    }

    private List<SearchResultItemDTO> convertItems(List<SearchResultDetailVO.SearchItem> items) {
        if (items == null) return Collections.emptyList();
        return items.stream().map(item -> {
            SearchResultItemDTO dto = new SearchResultItemDTO();
            dto.setContent(item.getContent());
            dto.setScore(item.getScore());
            dto.setSource(item.getSource());
            dto.setChunkIndex(item.getChunkIndex());
            return dto;
        }).collect(Collectors.toList());
    }

    private TenantScopeVO currentScope(HttpServletRequest request) {
        return TenantScopeSupport.currentScope(request);
    }

    /**
     * 文档删除审计兜底
     */
    private void safeAuditDelete(Long userId, String username, String documentId,
                                 AuditResult result, String detail, String ip, String ua) {
        try {
            if (auditLogService != null) {
                auditLogService.recordAsync(userId, username, AuditAction.DELETE_DOC,
                        "DOCUMENT", documentId == null ? "" : documentId,
                        result, "", detail, ip, ua);
            }
        } catch (Exception ex) {
            log.warn("审计调用失败：action=DELETE_DOC, err={}", ex.getMessage());
        }
    }
}
