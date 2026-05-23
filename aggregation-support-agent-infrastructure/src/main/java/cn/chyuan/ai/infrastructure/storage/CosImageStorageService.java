package cn.chyuan.ai.infrastructure.storage;

import cn.chyuan.ai.domain.storage.config.StorageProperties;
import cn.chyuan.ai.domain.storage.service.IImageStorageService;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.region.Region;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 腾讯云COS存储服务实现
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "storage.image", name = "provider", havingValue = "cos")
public class CosImageStorageService implements IImageStorageService, DisposableBean {

    private final StorageProperties storageProperties;
    private COSClient cosClient;

    public CosImageStorageService(StorageProperties storageProperties) {
        this.storageProperties = storageProperties;
    }

    @PostConstruct
    public void init() {
        try {
            StorageProperties.Cos cosConfig = storageProperties.getCos();

            // 初始化COS凭据
            COSCredentials cred = new BasicCOSCredentials(cosConfig.getSecretId(), cosConfig.getSecretKey());

            // 设置区域
            Region region = new Region(cosConfig.getRegion());
            ClientConfig clientConfig = new ClientConfig(region);

            // 初始化客户端
            cosClient = new COSClient(cred, clientConfig);

            log.info("腾讯云COS客户端初始化成功: region={}, bucket={}", cosConfig.getRegion(), cosConfig.getBucket());
        } catch (Exception e) {
            log.error("腾讯云COS客户端初始化失败", e);
        }
    }

    @Override
    public String upload(MultipartFile file, String userId, String fileName) throws Exception {
        StorageProperties.Cos cosConfig = storageProperties.getCos();

        // 构建COS对象键
        String key = buildObjectKey(userId, fileName);

        // 创建临时文件
        File tempFile = File.createTempFile("upload_", "_" + fileName);
        file.transferTo(tempFile);

        try {
            // 上传到COS
            PutObjectRequest putObjectRequest = new PutObjectRequest(
                    cosConfig.getBucket(),
                    key,
                    tempFile
            );

            PutObjectResult result = cosClient.putObject(putObjectRequest);

            log.info("腾讯云COS文件上传成功: userId={}, fileName={}, eTag={}",
                    userId, fileName, result.getETag());

            return getImageUrl(userId, fileName);
        } finally {
            // 删除临时文件
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    @Override
    public InputStream getInputStream(String userId, String fileName) throws Exception {
        StorageProperties.Cos cosConfig = storageProperties.getCos();
        String key = buildObjectKey(userId, fileName);

        GetObjectRequest getObjectRequest = new GetObjectRequest(
                cosConfig.getBucket(),
                key
        );

        return cosClient.getObject(getObjectRequest).getObjectContent();
    }

    @Override
    public void delete(String userId, String fileName) throws Exception {
        StorageProperties.Cos cosConfig = storageProperties.getCos();
        String key = buildObjectKey(userId, fileName);

        cosClient.deleteObject(cosConfig.getBucket(), key);

        log.info("腾讯云COS文件删除成功: userId={}, fileName={}", userId, fileName);
    }

    @Override
    public List<String> listImages(String userId) throws Exception {
        StorageProperties.Cos cosConfig = storageProperties.getCos();
        String prefix = userId + "/";

        List<String> fileNames = new ArrayList<>();

        cosClient.listObjects(cosConfig.getBucket(), prefix).getObjectSummaries()
                .forEach(summary -> {
                    String key = summary.getKey();
                    if (key.length() > prefix.length()) {
                        String fileName = key.substring(prefix.length());
                        if (isImageFile(fileName)) {
                            fileNames.add(fileName);
                        }
                    }
                });

        return fileNames;
    }

    @Override
    public String getImageUrl(String userId, String fileName) {
        StorageProperties.Cos cosConfig = storageProperties.getCos();

        // 如果配置了自定义URL前缀，使用自定义前缀
        if (cosConfig.getUrlPrefix() != null && !cosConfig.getUrlPrefix().isEmpty()) {
            return cosConfig.getUrlPrefix() + "/" + buildObjectKey(userId, fileName);
        }

        // 否则使用腾讯云COS默认URL格式
        return String.format("https://%s.cos.%s.myqcloud.com/%s",
                cosConfig.getBucket(),
                cosConfig.getRegion(),
                buildObjectKey(userId, fileName));
    }

    @Override
    public boolean exists(String userId, String fileName) throws Exception {
        StorageProperties.Cos cosConfig = storageProperties.getCos();
        String key = buildObjectKey(userId, fileName);

        try {
            return cosClient.doesObjectExist(cosConfig.getBucket(), key);
        } catch (Exception e) {
            log.error("检查COS文件是否存在失败: userId={}, fileName={}", userId, fileName, e);
            return false;
        }
    }

    @Override
    public void destroy() throws Exception {
        if (cosClient != null) {
            cosClient.shutdown();
            log.info("腾讯云COS客户端已关闭");
        }
    }

    /**
     * 构建COS对象键
     */
    private String buildObjectKey(String userId, String fileName) {
        return userId + "/" + fileName;
    }

    /**
     * 判断是否为图片文件
     */
    private boolean isImageFile(String fileName) {
        String extension = getFileExtension(fileName).toLowerCase();
        return extension.matches("jpg|jpeg|png|gif|webp|bmp");
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        return lastDotIndex > 0 ? fileName.substring(lastDotIndex + 1) : "";
    }
}