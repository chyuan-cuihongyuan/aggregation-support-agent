package cn.chyuan.ai.domain.multimodal.service.impl;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.multimodal.model.entity.ImageEntity;
import cn.chyuan.ai.domain.multimodal.model.valobj.CrossModalSearchResultVO;
import cn.chyuan.ai.domain.multimodal.service.IMultimodalService;
import cn.chyuan.ai.domain.storage.service.IImageStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 默认多模态服务实现。
 *
 * 当前代码库尚未接入图片 embedding / OCR，因此只提供已有能力范围内的租户安全基础检索：
 * 以文搜图按当前用户图片文件名做匹配；以图搜文返回空结果。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "multimodal.enabled", havingValue = "true", matchIfMissing = true)
public class DefaultMultimodalService implements IMultimodalService {

    private final IImageStorageService imageStorageService;

    @Override
    public ImageEntity uploadImage(String fileName, byte[] imageData, String mimeType, String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        if (fileName == null || fileName.isEmpty()) {
            fileName = UUID.randomUUID() + ".png";
        }
        try {
            String imageUrl = imageStorageService.upload(
                    new ByteArrayMultipartFile(fileName, imageData, mimeType),
                    userId,
                    fileName
            );
            return ImageEntity.builder()
                    .imageId(fileName)
                    .fileName(fileName)
                    .filePath(imageUrl)
                    .fileSize(imageData == null ? 0L : (long) imageData.length)
                    .mimeType(mimeType)
                    .userId(userId)
                    .createdAt(new Date())
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("图片上传失败", e);
        }
    }

    @Override
    public List<CrossModalSearchResultVO> textToImageSearch(String query, int topK, TenantScopeVO scope) {
        String userId = requireOwnerUserId(scope);
        String normalizedQuery = normalize(query);
        if (normalizedQuery.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            return imageStorageService.listImages(userId).stream()
                    .map(fileName -> toImageResult(userId, fileName, normalizedQuery))
                    .filter(result -> result.getScore() > 0F)
                    .sorted((a, b) -> Float.compare(b.getScore(), a.getScore()))
                    .limit(Math.max(topK, 0))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("以文搜图失败: userId={}, err={}", userId, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<CrossModalSearchResultVO> imageToTextSearch(byte[] imageData, int topK, TenantScopeVO scope) {
        requireOwnerUserId(scope);
        if (imageData == null || imageData.length == 0) {
            return Collections.emptyList();
        }
        log.debug("图片到文本检索需要 OCR 或图片 embedding 服务，当前返回空结果");
        return Collections.emptyList();
    }

    @Override
    public List<ImageEntity> listImages(String userId) {
        try {
            return imageStorageService.listImages(userId).stream()
                    .map(fileName -> ImageEntity.builder()
                            .imageId(fileName)
                            .fileName(fileName)
                            .filePath(imageStorageService.getImageUrl(userId, fileName))
                            .userId(userId)
                            .build())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("查询图片列表失败: userId={}, err={}", userId, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public void deleteImage(String imageId) {
        throw new UnsupportedOperationException("删除图片需要显式用户作用域");
    }

    private CrossModalSearchResultVO toImageResult(String userId, String fileName, String normalizedQuery) {
        String normalizedFileName = normalize(fileName);
        float score = scoreFileName(normalizedFileName, normalizedQuery);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("fileName", fileName);
        metadata.put("matchType", "fileName");

        return CrossModalSearchResultVO.builder()
                .itemId(fileName)
                .modalityType("IMAGE")
                .content(fileName)
                .imageUrl(imageStorageService.getImageUrl(userId, fileName))
                .score(score)
                .metadata(metadata)
                .build();
    }

    private float scoreFileName(String normalizedFileName, String normalizedQuery) {
        if (normalizedFileName.equals(normalizedQuery)) {
            return 1F;
        }
        if (normalizedFileName.contains(normalizedQuery)) {
            return 0.8F;
        }

        long matchedTokens = Arrays.stream(normalizedQuery.split("\\s+"))
                .filter(token -> !token.isEmpty())
                .filter(normalizedFileName::contains)
                .count();
        long totalTokens = Arrays.stream(normalizedQuery.split("\\s+"))
                .filter(token -> !token.isEmpty())
                .count();
        if (totalTokens == 0 || matchedTokens == 0) {
            return 0F;
        }
        return (float) matchedTokens / totalTokens * 0.6F;
    }

    private String requireOwnerUserId(TenantScopeVO scope) {
        if (scope == null || scope.getOwnerUserId() == null || scope.getOwnerUserId().isEmpty()) {
            throw new IllegalArgumentException("缺失租户作用域");
        }
        return scope.getOwnerUserId();
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .trim();
    }

    private static class ByteArrayMultipartFile implements MultipartFile {

        private final String fileName;
        private final byte[] content;
        private final String contentType;

        private ByteArrayMultipartFile(String fileName, byte[] content, String contentType) {
            this.fileName = fileName;
            this.content = content == null ? new byte[0] : content;
            this.contentType = contentType;
        }

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return fileName;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() {
            return content;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(java.io.File dest) throws IOException {
            org.springframework.util.FileCopyUtils.copy(content, dest);
        }
    }
}
