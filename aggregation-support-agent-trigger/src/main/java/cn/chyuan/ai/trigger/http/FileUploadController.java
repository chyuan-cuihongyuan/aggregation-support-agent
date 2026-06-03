package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.dto.UploadResponseDTO;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.audit.service.IAuditLogService;
import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.knowledgebase.adapter.repository.IKnowledgeBaseRepository;
import cn.chyuan.ai.domain.knowledgebase.model.entity.KnowledgeBaseEntity;
import cn.chyuan.ai.domain.rag.adapter.repository.IDocumentMetadataRepository;
import cn.chyuan.ai.domain.rag.model.entity.DocumentMetadataEntity;
import cn.chyuan.ai.domain.rag.model.valobj.DocumentUploadCommand;
import cn.chyuan.ai.domain.rag.service.IRagService;
import cn.chyuan.ai.trigger.filter.JwtAuthFilter;
import cn.chyuan.ai.trigger.support.AuditContextSupport;
import cn.chyuan.ai.trigger.support.TenantScopeSupport;
import cn.chyuan.ai.types.enums.AuditAction;
import cn.chyuan.ai.types.enums.AuditResult;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 文件上传控制器 — 处理文档上传并自动向量化存储到 Milvus
 * <p>
 * 支持 TXT 和 Markdown 文件上传，上传后自动执行：
 * 文本解析 → 分块(800字符,100重叠) → 嵌入向量化 → Milvus 存储
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
public class FileUploadController {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "txt", "md", "markdown", "mdown", "mkd", "pdf", "doc", "docx", "html", "htm", "csv", "xls", "xlsx"
    );

    private static final Set<String> SUPPORTED_MIME_TYPES = Set.of(
            "text/plain",
            "text/csv",
            "text/markdown",
            "text/x-markdown",
            "text/html",
            "application/xhtml+xml",
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/octet-stream"
    );

    @Autowired(required = false)
    private IRagService ragService;

    @Resource
    private IAuditLogService auditLogService;

    @Autowired(required = false)
    private IDocumentMetadataRepository documentMetadataRepository;

    @Autowired(required = false)
    private IKnowledgeBaseRepository knowledgeBaseRepository;

    @Autowired(required = false)
    @Qualifier("ragDocumentExecutor")
    private AsyncTaskExecutor ragDocumentExecutor;

    @Value("${rag.upload.max-size-mb}")
    private long maxUploadSizeMb;

    /**
     * 上传文档到知识库 — 文件内容被自动分块、向量化和存储
     *
     * @param file 上传的文件（支持 .txt 和 .md 格式）
     * @return 上传结果（文档 ID、分块数量、状态）
     */
    @RequestMapping(value = "upload", method = RequestMethod.POST)
    public Response<UploadResponseDTO> uploadDocument(
            HttpServletRequest request,
            @RequestParam(value = "knowledgeBaseId", required = false) String knowledgeBaseId,
            @RequestParam("file") MultipartFile file) {
        String ip = AuditContextSupport.extractIp(request);
        String ua = AuditContextSupport.extractUserAgent(request);
        String fileName = file == null ? "" : file.getOriginalFilename();
        // 提取登录用户 — 此接口经过 JwtAuthFilter，attr 必存在
        Object uidAttr = request.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        Object unameAttr = request.getAttribute(JwtAuthFilter.ATTR_USERNAME);
        Long auditUserId = (uidAttr instanceof Long) ? (Long) uidAttr : 0L;
        String auditUsername = (unameAttr instanceof String) ? (String) unameAttr : "";
        try {
            String validationError = validateUploadFile(file);
            if (validationError != null) {
                safeAudit(auditUserId, auditUsername, AuditResult.FAILURE,
                        fileName, validationError, ip, ua);
                return Response.<UploadResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info(validationError)
                        .build();
            }

            if (ragService == null) {
                safeAudit(auditUserId, auditUsername, AuditResult.FAILURE,
                        fileName, "RAG服务未启用", ip, ua);
                return Response.<UploadResponseDTO>builder()
                        .code(ResponseCode.UN_ERROR.getCode())
                        .info("RAG服务未启用，请配置milvus.enabled=true")
                        .build();
            }

            String originalFilename = file.getOriginalFilename();
            String contentType = file.getContentType();
            log.info("接收文档上传: fileName={}, contentType={}, size={}", originalFilename, contentType, file.getSize());
            TenantScopeVO scope = TenantScopeSupport.currentScope(request);
            KnowledgeBaseEntity knowledgeBase = resolveKnowledgeBase(knowledgeBaseId, scope);
            String documentId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

            DocumentUploadCommand command = DocumentUploadCommand.builder()
                    .documentId(documentId)
                    .fileName(originalFilename)
                    .rawContent(file.getBytes())
                    .mimeType(contentType)
                    .userId(scope.getOwnerUserId())
                    .tenantId(scope.getTenantId())
                    .knowledgeBaseId(knowledgeBase != null ? knowledgeBase.getKnowledgeBaseId() : "")
                    .knowledgeBaseName(knowledgeBase != null ? knowledgeBase.getName() : "")
                    .build();

            ragService.uploadDocument(command);

            // 上传成功审计
            safeAudit(auditUserId, auditUsername, AuditResult.SUCCESS,
                    originalFilename, "", ip, ua);

            UploadResponseDTO dto = buildUploadResponse(documentId);
            return Response.<UploadResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(dto)
                    .build();

        } catch (AppException e) {
            log.error("文档上传处理异常", e);
            safeAudit(auditUserId, auditUsername, AuditResult.FAILURE,
                    fileName, e.getInfo(), ip, ua);
            return Response.<UploadResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("文档上传失败: {}", fileName, e);
            safeAudit(auditUserId, auditUsername, AuditResult.FAILURE,
                    fileName, "文档上传失败: " + e.getMessage(), ip, ua);
            return Response.<UploadResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("文档上传失败: " + e.getMessage())
                    .build();
        }
    }

    @RequestMapping(value = "rag/documents/async", method = RequestMethod.POST)
    public Response<UploadResponseDTO> uploadDocumentAsync(
            HttpServletRequest request,
            @RequestParam(value = "knowledgeBaseId", required = false) String knowledgeBaseId,
            @RequestParam("file") MultipartFile file) {
        String ip = AuditContextSupport.extractIp(request);
        String ua = AuditContextSupport.extractUserAgent(request);
        String fileName = file == null ? "" : file.getOriginalFilename();
        Object uidAttr = request.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        Object unameAttr = request.getAttribute(JwtAuthFilter.ATTR_USERNAME);
        Long auditUserId = (uidAttr instanceof Long) ? (Long) uidAttr : 0L;
        String auditUsername = (unameAttr instanceof String) ? (String) unameAttr : "";
        try {
            String validationError = validateUploadFile(file);
            if (validationError != null) {
                safeAudit(auditUserId, auditUsername, AuditResult.FAILURE,
                        fileName, validationError, ip, ua);
                return Response.<UploadResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info(validationError)
                        .build();
            }
            if (ragService == null || documentMetadataRepository == null) {
                safeAudit(auditUserId, auditUsername, AuditResult.FAILURE,
                        fileName, "RAG服务未启用", ip, ua);
                return Response.<UploadResponseDTO>builder()
                        .code(ResponseCode.UN_ERROR.getCode())
                        .info("RAG服务未启用，请配置milvus.enabled=true")
                        .build();
            }

            String documentId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            TenantScopeVO scope = TenantScopeSupport.currentScope(request);
            KnowledgeBaseEntity knowledgeBase = resolveKnowledgeBase(knowledgeBaseId, scope);
            byte[] rawContent = file.getBytes();
            String originalFilename = file.getOriginalFilename();
            String contentType = file.getContentType();
            DocumentUploadCommand command = DocumentUploadCommand.builder()
                    .documentId(documentId)
                    .fileName(originalFilename)
                    .rawContent(rawContent)
                    .mimeType(contentType)
                    .userId(scope.getOwnerUserId())
                    .tenantId(scope.getTenantId())
                    .knowledgeBaseId(knowledgeBase != null ? knowledgeBase.getKnowledgeBaseId() : "")
                    .knowledgeBaseName(knowledgeBase != null ? knowledgeBase.getName() : "")
                    .build();

            executeDocumentProcessing(command, auditUserId, auditUsername, originalFilename, ip, ua);

            UploadResponseDTO dto = new UploadResponseDTO();
            dto.setDocumentId(documentId);
            dto.setStatus("processing");
            dto.setMessage(knowledgeBase != null
                    ? "文档已进入异步处理队列，所属知识库：" + knowledgeBase.getName()
                    : "文档已进入异步处理队列");
            return Response.<UploadResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(dto)
                    .build();
        } catch (AppException e) {
            log.error("异步文档上传处理异常", e);
            safeAudit(auditUserId, auditUsername, AuditResult.FAILURE,
                    fileName, e.getInfo(), ip, ua);
            return Response.<UploadResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("异步文档上传失败: {}", fileName, e);
            safeAudit(auditUserId, auditUsername, AuditResult.FAILURE,
                    fileName, "文档上传失败: " + e.getMessage(), ip, ua);
            return Response.<UploadResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("文档上传失败: " + e.getMessage())
                    .build();
        }
    }

    @RequestMapping(value = "rag/documents/{documentId}/status", method = RequestMethod.GET)
    public Response<UploadResponseDTO> getDocumentProcessStatus(
            HttpServletRequest request,
            @PathVariable("documentId") String documentId) {
        try {
            if (documentMetadataRepository == null) {
                return Response.<UploadResponseDTO>builder()
                        .code(ResponseCode.UN_ERROR.getCode())
                        .info("RAG服务未启用")
                        .build();
            }
            DocumentMetadataEntity entity = documentMetadataRepository.queryByDocumentId(
                    documentId,
                    TenantScopeSupport.currentScope(request)
            );
            if (entity == null) {
                return Response.<UploadResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("文档不存在")
                        .build();
            }
            UploadResponseDTO dto = new UploadResponseDTO();
            dto.setDocumentId(entity.getDocumentId());
            dto.setChunkCount(entity.getTotalChunks());
            dto.setStatus(entity.getProcessingStatus());
            dto.setMessage(entity.getErrorMessage());
            return Response.<UploadResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(dto)
                    .build();
        } catch (Exception e) {
            log.error("查询文档处理状态失败: {}", documentId, e);
            return Response.<UploadResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("查询失败: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 上传审计写入兜底
     */
    private void safeAudit(Long userId, String username, AuditResult result,
                           String resourceId, String detail, String ip, String ua) {
        try {
            if (auditLogService != null) {
                auditLogService.recordAsync(userId, username, AuditAction.UPLOAD_DOC,
                        "DOCUMENT", resourceId == null ? "" : resourceId,
                        result, "", detail, ip, ua);
            }
        } catch (Exception ex) {
            log.warn("审计调用失败：action=UPLOAD_DOC, err={}", ex.getMessage());
        }
    }

    private void executeDocumentProcessing(DocumentUploadCommand command,
                                           Long auditUserId,
                                           String auditUsername,
                                           String fileName,
                                           String ip,
                                           String ua) {
        Runnable task = () -> {
            try {
                ragService.uploadDocument(command);
                safeAudit(auditUserId, auditUsername, AuditResult.SUCCESS, fileName, "", ip, ua);
            } catch (Exception ex) {
                String message = ex.getMessage() == null ? "未知错误" : ex.getMessage();
                String errorMessage = message.length() > 500 ? message.substring(0, 500) : message;
                log.error("异步文档处理失败: documentId={}, fileName={}", command.getDocumentId(), fileName, ex);
                try {
                    documentMetadataRepository.updateStatus(command.getDocumentId(), "failed", 0, 0, 0, errorMessage);
                } catch (Exception updateEx) {
                    log.warn("异步文档处理失败状态更新异常: documentId={}, err={}", command.getDocumentId(), updateEx.getMessage());
                }
                safeAudit(auditUserId, auditUsername, AuditResult.FAILURE, fileName, errorMessage, ip, ua);
            }
        };
        if (ragDocumentExecutor != null) {
            ragDocumentExecutor.execute(task);
        } else {
            new Thread(task, "rag-document-fallback").start();
        }
    }

    private String validateUploadFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return "上传文件不能为空";
        }
        long maxBytes = maxUploadSizeMb * 1024 * 1024;
        if (maxBytes > 0 && file.getSize() > maxBytes) {
            return "文件大小超过" + maxUploadSizeMb + "MB限制";
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            return "文件名不能为空";
        }
        String extension = getExtension(originalFilename);
        if (extension.isBlank() || !SUPPORTED_EXTENSIONS.contains(extension)) {
            return "不支持的文件类型，仅支持 txt、md、pdf、doc、docx、html、csv、xls、xlsx";
        }
        String contentType = file.getContentType();
        if (contentType != null && !contentType.isBlank()) {
            String normalizedContentType = contentType.toLowerCase(Locale.ROOT);
            int semicolonIndex = normalizedContentType.indexOf(';');
            if (semicolonIndex >= 0) {
                normalizedContentType = normalizedContentType.substring(0, semicolonIndex).trim();
            }
            if (!SUPPORTED_MIME_TYPES.contains(normalizedContentType)) {
                return "不支持的文件MIME类型: " + contentType;
            }
        }
        return null;
    }

    private String getExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private KnowledgeBaseEntity resolveKnowledgeBase(String knowledgeBaseId, TenantScopeVO scope) {
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            return null;
        }
        if (knowledgeBaseRepository == null) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), "知识库服务未启用");
        }
        KnowledgeBaseEntity entity = knowledgeBaseRepository.queryById(knowledgeBaseId, scope);
        if (entity == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "知识库不存在或无权限");
        }
        return entity;
    }

    private UploadResponseDTO buildUploadResponse(String documentId) {
        UploadResponseDTO dto = new UploadResponseDTO();
        dto.setDocumentId(documentId);
        dto.setStatus("success");
        dto.setMessage("文档上传成功");
        if (documentMetadataRepository != null) {
            DocumentMetadataEntity entity = documentMetadataRepository.queryByDocumentId(documentId);
            if (entity != null) {
                dto.setChunkCount(entity.getTotalChunks());
                dto.setStatus(entity.getProcessingStatus());
                dto.setMessage(entity.getErrorMessage() != null && !entity.getErrorMessage().isBlank()
                        ? entity.getErrorMessage()
                        : "文档上传成功");
            }
        }
        return dto;
    }

}
