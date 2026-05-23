package cn.chyuan.ai.domain.storage.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 图片存储配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "storage.image")
public class StorageProperties {

    /**
     * 存储提供商：local、oss、cos
     */
    private String provider = "local";

    /**
     * 本地存储配置
     */
    private Local local = new Local();

    /**
     * 阿里云OSS配置
     */
    private Oss oss = new Oss();

    /**
     * 腾讯云COS配置
     */
    private Cos cos = new Cos();

    @Data
    public static class Local {
        /**
         * 基础路径
         */
        private String basePath = "./data/images";

        /**
         * URL前缀
         */
        private String urlPrefix = "/api/v1/images";
    }

    @Data
    public static class Oss {
        /**
         * OSS端点
         */
        private String endpoint;

        /**
         * 存储桶名称
         */
        private String bucketName;

        /**
         * 访问密钥ID
         */
        private String accessKeyId;

        /**
         * 访问密钥Secret
         */
        private String accessKeySecret;

        /**
         * URL前缀
         */
        private String urlPrefix;
    }

    @Data
    public static class Cos {
        /**
         * SecretId
         */
        private String secretId;

        /**
         * SecretKey
         */
        private String secretKey;

        /**
         * 地域
         */
        private String region = "ap-beijing";

        /**
         * 存储桶名称
         */
        private String bucket;

        /**
         * URL前缀
         */
        private String urlPrefix;
    }
}