package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.dto.UploadResponseDTO;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.rag.model.valobj.DocumentUploadCommand;
import cn.chyuan.ai.domain.rag.service.IRagService;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
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
@CrossOrigin(origins = "*")
public class FileUploadController {

    @Autowired(required = false)
    private IRagService ragService;

    /**
     * 上传文档到知识库 — 文件内容被自动分块、向量化和存储
     *
     * @param file 上传的文件（支持 .txt 和 .md 格式）
     * @return 上传结果（文档 ID、分块数量、状态）
     */
    @RequestMapping(value = "upload", method = RequestMethod.POST)
    public Response<UploadResponseDTO> uploadDocument(@RequestParam("file") MultipartFile file) {
        try {
            // 校验文件
            if (file.isEmpty()) {
                return Response.<UploadResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("上传文件不能为空")
                        .build();
            }

            if (ragService == null) {
                return Response.<UploadResponseDTO>builder()
                        .code(ResponseCode.UN_ERROR.getCode())
                        .info("RAG服务未启用，请配置milvus.enabled=true")
                        .build();
            }

            String originalFilename = file.getOriginalFilename();
            String contentType = file.getContentType();
            log.info("接收文档上传: fileName={}, contentType={}, size={}", originalFilename, contentType, file.getSize());

            // 读取文件内容为 UTF-8 文本
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);

            // 构建上传命令并调用 RAG 服务处理（分块 → 嵌入 → 存储）
            DocumentUploadCommand command = DocumentUploadCommand.builder()
                    .fileName(originalFilename)
                    .content(content)
                    .mimeType(contentType)
                    .build();

            // 生成文档 ID 用于追踪
            String documentId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

            ragService.uploadDocument(command);

            UploadResponseDTO responseDTO = new UploadResponseDTO();
            responseDTO.setDocumentId(documentId);
            responseDTO.setChunkCount(0); // 实际块数由 RagService 内部计算
            responseDTO.setStatus("success");
            responseDTO.setMessage("文档上传并处理成功");

            return Response.<UploadResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();

        } catch (AppException e) {
            log.error("文档上传处理异常", e);
            return Response.<UploadResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("文档上传失败: {}", file.getOriginalFilename(), e);
            return Response.<UploadResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("文档上传失败: " + e.getMessage())
                    .build();
        }
    }

}
