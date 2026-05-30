package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.dto.ImageDTO;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.storage.service.IImageStorageService;
import cn.chyuan.ai.trigger.support.CurrentUserSupport;
import cn.chyuan.ai.types.enums.ResponseCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 图片管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/images")
@RequiredArgsConstructor
public class ImageController {

    private final IImageStorageService imageStorageService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 查询用户图片列表
     */
    @GetMapping
    public Response<List<ImageDTO>> listImages(HttpServletRequest request) {
        String userId = CurrentUserSupport.requireUserIdString(request);
        try {
            List<ImageDTO> images = new ArrayList<>();
            List<String> fileNames = imageStorageService.listImages(userId);

            for (String fileName : fileNames) {
                ImageDTO dto = new ImageDTO();
                dto.setImageId(fileName);
                dto.setFileName(fileName);
                // 注意：文件大小和创建时间对于云存储可能无法直接获取
                dto.setImageUrl(imageStorageService.getImageUrl(userId, fileName));
                images.add(dto);
            }

            return Response.<List<ImageDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(images)
                    .build();
        } catch (Exception e) {
            log.error("查询图片列表失败", e);
            return Response.<List<ImageDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("查询图片列表失败: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 上传图片
     */
    @PostMapping("/upload")
    public Response<ImageDTO> uploadImage(
            HttpServletRequest request,
            @RequestParam("file") MultipartFile file) {
        String userId = CurrentUserSupport.requireUserIdString(request);
        try {
            if (file.isEmpty()) {
                return Response.<ImageDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("上传文件不能为空")
                        .build();
            }

            String originalFilename = file.getOriginalFilename();
            if (!isImageFile(originalFilename)) {
                return Response.<ImageDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("只支持图片格式（jpg、jpeg、png、gif、webp）")
                        .build();
            }

            // 生成唯一文件名
            String extension = getFileExtension(originalFilename);
            String uniqueFileName = UUID.randomUUID().toString() + "." + extension;

            // 上传文件
            String imageUrl = imageStorageService.upload(file, userId, uniqueFileName);

            // 构建返回对象
            ImageDTO dto = new ImageDTO();
            dto.setImageId(uniqueFileName);
            dto.setFileName(originalFilename);
            dto.setFileSize(file.getSize());
            dto.setMimeType(file.getContentType());
            dto.setImageUrl(imageUrl);
            dto.setCreatedAt(LocalDateTime.now().format(FORMATTER));

            log.info("图片上传成功: userId={}, fileName={}", userId, uniqueFileName);

            return Response.<ImageDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(dto)
                    .build();

        } catch (Exception e) {
            log.error("图片上传失败", e);
            return Response.<ImageDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("图片上传失败: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 获取图片文件
     */
    @GetMapping("/{userId}/{fileName}")
    public byte[] getImage(HttpServletRequest request, @PathVariable String userId, @PathVariable String fileName) {
        validateFileName(fileName);
        String currentUserId = CurrentUserSupport.requireUserIdString(request);
        if (!currentUserId.equals(userId)) {
            log.warn("IDOR attempt: currentUserId={}, requestedUserId={}", currentUserId, userId);
            return null;
        }
        try (InputStream inputStream = imageStorageService.getInputStream(userId, fileName)) {
            if (inputStream == null) {
                return null;
            }
            return inputStream.readAllBytes();
        } catch (Exception e) {
            log.error("读取图片失败: userId={}, fileName={}", userId, fileName, e);
            return null;
        }
    }

    /**
     * 删除图片
     */
    @DeleteMapping("/{userId}/{fileName}")
    public Response<String> deleteImage(HttpServletRequest request, @PathVariable String userId, @PathVariable String fileName) {
        validateFileName(fileName);
        String currentUserId = CurrentUserSupport.requireUserIdString(request);
        if (!currentUserId.equals(userId)) {
            log.warn("IDOR attempt: currentUserId={}, requestedUserId={}", currentUserId, userId);
            return Response.<String>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info("无权操作其他用户的资源")
                    .build();
        }
        try {
            imageStorageService.delete(userId, fileName);
            log.info("图片删除成功: userId={}, fileName={}", userId, fileName);
            return Response.<String>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info("删除成功")
                    .build();
        } catch (Exception e) {
            log.error("删除图片失败", e);
            return Response.<String>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("删除图片失败: " + e.getMessage())
                    .build();
        }
    }

    private void validateFileName(String fileName) {
        if (fileName == null || fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            throw new IllegalArgumentException("非法文件名: " + fileName);
        }
    }

    private boolean isImageFile(String fileName) {
        String extension = getFileExtension(fileName).toLowerCase();
        return extension.matches("jpg|jpeg|png|gif|webp|bmp");
    }

    private String getFileExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        return lastDotIndex > 0 ? fileName.substring(lastDotIndex + 1) : "";
    }

    private String getMimeType(String fileName) {
        String extension = getFileExtension(fileName).toLowerCase();
        return switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            default -> "application/octet-stream";
        };
    }
}
