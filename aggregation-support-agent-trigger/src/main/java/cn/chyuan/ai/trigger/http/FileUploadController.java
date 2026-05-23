package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.dto.UploadResponseDTO;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.audit.service.IAuditLogService;
import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.rag.model.valobj.DocumentUploadCommand;
import cn.chyuan.ai.domain.rag.service.IRagService;
import cn.chyuan.ai.trigger.filter.JwtAuthFilter;
import cn.chyuan.ai.trigger.support.AuditContextSupport;
import cn.chyuan.ai.trigger.support.CurrentUserSupport;
import cn.chyuan.ai.types.enums.AuditAction;
import cn.chyuan.ai.types.enums.AuditResult;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;

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

    @Autowired(required = false)
    private IRagService ragService;

    @Resource
    private IAuditLogService auditLogService;

    /**
     * 上传文档到知识库 — 文件内容被自动分块、向量化和存储
     *
     * @param file 上传的文件（支持 .txt 和 .md 格式）
     * @return 上传结果（文档 ID、分块数量、状态）
     */
    @RequestMapping(value = "upload", method = RequestMethod.POST)
    public Response<UploadResponseDTO> uploadDocument(
            HttpServletRequest request,
            @RequestParam("file") MultipartFile file) {
        String ip = AuditContextSupport.extractIp(request);
        String ua = AuditContextSupport.extractUserAgent(request);
        String fileName = file.getOriginalFilename();
        // 提取登录用户 — 此接口经过 JwtAuthFilter，attr 必存在
        Object uidAttr = request.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        Object unameAttr = request.getAttribute(JwtAuthFilter.ATTR_USERNAME);
        Long auditUserId = (uidAttr instanceof Long) ? (Long) uidAttr : 0L;
        String auditUsername = (unameAttr instanceof String) ? (String) unameAttr : "";
        try {
            if (file.isEmpty()) {
                safeAudit(auditUserId, auditUsername, AuditResult.FAILURE,
                        fileName, "上传文件不能为空", ip, ua);
                return Response.<UploadResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("上传文件不能为空")
                        .build();
            }

            if (file.getSize() > 50 * 1024 * 1024) {
                safeAudit(auditUserId, auditUsername, AuditResult.FAILURE,
                        fileName, "文件大小超过50MB限制", ip, ua);
                return Response.<UploadResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("文件大小超过50MB限制")
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
            TenantScopeVO scope = TenantScopeVO.singleUser(CurrentUserSupport.requireUserIdString(request));

            DocumentUploadCommand command = DocumentUploadCommand.builder()
                    .fileName(originalFilename)
                    .rawContent(file.getBytes())
                    .mimeType(contentType)
                    .userId(scope.getOwnerUserId())
                    .tenantId(scope.getTenantId())
                    .build();

            ragService.uploadDocument(command);

            // 上传成功审计
            safeAudit(auditUserId, auditUsername, AuditResult.SUCCESS,
                    originalFilename, "", ip, ua);

            return Response.<UploadResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
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
            log.error("文档上传失败: {}", file.getOriginalFilename(), e);
            safeAudit(auditUserId, auditUsername, AuditResult.FAILURE,
                    fileName, "文档上传失败: " + e.getMessage(), ip, ua);
            return Response.<UploadResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("文档上传失败: " + e.getMessage())
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

}
