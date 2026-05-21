package cn.chyuan.ai.infrastructure.storage;

import cn.chyuan.ai.domain.storage.service.IImageStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 图片存储服务配置
 * 根据配置选择存储策略
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageStorageConfiguration {

    private final LocalImageStorageService localImageStorageService;
    private final CosImageStorageService cosImageStorageService;

    /**
     * 本地文件系统存储服务（Primary当provider=local时）
     */
    @Primary
    @org.springframework.context.annotation.Bean
    @ConditionalOnProperty(prefix = "storage.image", name = "provider", havingValue = "local", matchIfMissing = true)
    public IImageStorageService localImageStorageServicePrimary() {
        log.info("启用本地文件系统存储服务");
        return localImageStorageService;
    }

    /**
     * 腾讯云COS存储服务（Primary当provider=cos时）
     */
    @Primary
    @org.springframework.context.annotation.Bean
    @ConditionalOnProperty(prefix = "storage.image", name = "provider", havingValue = "cos")
    public IImageStorageService cosImageStorageServicePrimary() {
        log.info("启用腾讯云COS存储服务");
        return cosImageStorageService;
    }
}