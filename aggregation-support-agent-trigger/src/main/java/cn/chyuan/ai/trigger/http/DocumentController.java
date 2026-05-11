package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.dto.*;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.rag.adapter.repository.IDocumentMetadataRepository;
import cn.chyuan.ai.domain.rag.model.entity.DocumentMetadataEntity;
import cn.chyuan.ai.domain.rag.model.valobj.SearchResultDetailVO;
import cn.chyuan.ai.domain.rag.service.IRagService;
import cn.chyuan.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/documents")
@CrossOrigin(origins = "*")
public class DocumentController {

    @Autowired(required = false)
    private IRagService ragService;

    @Autowired
    private IDocumentMetadataRepository documentMetadataRepository;

    @GetMapping
    public Response<List<DocumentDTO>> listDocuments(
            @RequestParam(value = "userId", required = false, defaultValue = "") String userId) {
        try {
            List<DocumentMetadataEntity> entities = documentMetadataRepository.queryByUserId(userId);
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
    public Response<DocumentDTO> getDocument(@PathVariable String documentId) {
        try {
            DocumentMetadataEntity entity = documentMetadataRepository.queryByDocumentId(documentId);
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
    public Response<Void> deleteDocument(@PathVariable String documentId) {
        try {
            documentMetadataRepository.deleteByDocumentId(documentId);
            log.info("文档已删除: {}", documentId);
            return Response.<Void>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("删除文档失败: {}", documentId, e);
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("删除失败: " + e.getMessage())
                    .build();
        }
    }

    @PostMapping("/search")
    public Response<SearchTestResultDTO> searchTest(@RequestBody SearchTestRequestDTO request) {
        try {
            if (ragService == null) {
                return Response.<SearchTestResultDTO>builder()
                        .code(ResponseCode.UN_ERROR.getCode())
                        .info("RAG服务未启用")
                        .build();
            }

            SearchResultDetailVO detail = ragService.searchWithDetails(request.getQuery(), request.getTopK());

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
}
