package cn.chyuan.ai.infrastructure.storage;

import cn.chyuan.ai.domain.storage.config.StorageProperties;
import cn.chyuan.ai.domain.storage.service.IImageStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 本地文件系统存储服务实现
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "storage.image", name = "provider", havingValue = "local", matchIfMissing = true)
@RequiredArgsConstructor
public class LocalImageStorageService implements IImageStorageService {

    private final StorageProperties storageProperties;

    @Override
    public String upload(MultipartFile file, String userId, String fileName) throws Exception {
        Path userPath = Paths.get(storageProperties.getLocal().getBasePath(), userId);
        Files.createDirectories(userPath);

        Path targetPath = userPath.resolve(fileName);
        file.transferTo(targetPath.toFile());

        log.info("本地文件上传成功: userId={}, fileName={}", userId, fileName);
        return getImageUrl(userId, fileName);
    }

    @Override
    public InputStream getInputStream(String userId, String fileName) throws Exception {
        Path imagePath = Paths.get(storageProperties.getLocal().getBasePath(), userId, fileName);
        if (!Files.exists(imagePath)) {
            throw new IOException("文件不存在: " + imagePath);
        }
        return Files.newInputStream(imagePath);
    }

    @Override
    public void delete(String userId, String fileName) throws Exception {
        Path imagePath = Paths.get(storageProperties.getLocal().getBasePath(), userId, fileName);
        if (Files.exists(imagePath)) {
            Files.delete(imagePath);
            log.info("本地文件删除成功: userId={}, fileName={}", userId, fileName);
        }
    }

    @Override
    public List<String> listImages(String userId) throws Exception {
        List<String> fileNames = new ArrayList<>();
        Path userPath = Paths.get(storageProperties.getLocal().getBasePath(), userId);

        if (!Files.exists(userPath)) {
            return fileNames;
        }

        try (Stream<Path> paths = Files.list(userPath)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> isImageFile(path.getFileName().toString()))
                    .forEach(path -> fileNames.add(path.getFileName().toString()));
        }

        return fileNames;
    }

    @Override
    public String getImageUrl(String userId, String fileName) {
        return storageProperties.getLocal().getUrlPrefix() + "/" + userId + "/" + fileName;
    }

    @Override
    public boolean exists(String userId, String fileName) throws Exception {
        Path imagePath = Paths.get(storageProperties.getLocal().getBasePath(), userId, fileName);
        return Files.exists(imagePath);
    }

    private boolean isImageFile(String fileName) {
        String extension = getFileExtension(fileName).toLowerCase();
        return extension.matches("jpg|jpeg|png|gif|webp|bmp");
    }

    private String getFileExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        return lastDotIndex > 0 ? fileName.substring(lastDotIndex + 1) : "";
    }
}