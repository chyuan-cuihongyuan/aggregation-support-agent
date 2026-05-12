package cn.chyuan.ai.infrastructure.config;

import cn.chyuan.ai.domain.rag.adapter.repository.IVectorStoreRepository;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.Resource;

import org.springframework.context.annotation.Lazy;

/**
 * Milvus 客户端配置类 — 创建 MilvusServiceClient 单例 Bean 并在启动时确保集合存在
 * <p>
 * 使用 SmartInitializingSingleton 在所有单例 Bean 初始化完成后，
 * 自动调用 ensureCollection() 完成向量集合和索引的初始化，
 * 避免首次使用时因集合不存在而导致插入或检索失败。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "milvus.enabled", havingValue = "true", matchIfMissing = false)
public class MilvusClientConfig implements SmartInitializingSingleton {

    @Resource
    private MilvusConfigProperties milvusConfigProperties;

    @Lazy
    @Resource
    private IVectorStoreRepository vectorStoreRepository;

    /**
     * 创建 MilvusServiceClient Bean — 通过 gRPC 连接 Milvus 服务
     * <p>
     * 使用 ConnectParam 构建 gRPC 连接参数，包括主机地址和端口
     *
     * @return MilvusServiceClient 实例
     */
    @Bean
    public MilvusServiceClient milvusServiceClient() {
        log.info("初始化 Milvus 客户端连接: {}:{}", milvusConfigProperties.getHost(), milvusConfigProperties.getPort());

        ConnectParam connectParam = ConnectParam.newBuilder()
                .withHost(milvusConfigProperties.getHost())
                .withPort(milvusConfigProperties.getPort())
                .build();

        return new MilvusServiceClient(connectParam);
    }

    /**
     * 所有单例 Bean 初始化完成后回调 — 确保向量集合和索引已创建
     * <p>
     * 实现自 SmartInitializingSingleton，在 Spring 容器中所有单例 Bean
     * 全部实例化和依赖注入完成之后才会被调用，避免了循环依赖问题。
     */
    @Override
    public void afterSingletonsInstantiated() {
        try {
            vectorStoreRepository.ensureCollection();
            log.info("Milvus 集合初始化完成");
        } catch (Exception e) {
            log.warn("Milvus 集合初始化失败，将在首次使用时重试: {}", e.getMessage());
        }
    }

}
