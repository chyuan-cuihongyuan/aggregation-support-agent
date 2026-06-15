package cn.chyuan.ai.infrastructure.config.nacos;

import com.alibaba.nacos.api.config.listener.Listener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Nacos 配置监听器
 * 监听配置变更并触发相应的刷新操作
 */
@Component
public class NacosConfigListener implements Listener {

    private static final Logger log = LoggerFactory.getLogger(NacosConfigListener.class);

    private final Executor executor = Executors.newSingleThreadExecutor();

    @Override
    public Executor getExecutor() {
        return executor;
    }

    @Override
    public void receiveConfigInfo(String configInfo) {
        log.info("收到配置更新通知");
        log.debug("配置内容: {}", configInfo);

        // 在这里处理配置更新逻辑
        // 例如：刷新配置 Bean、重新加载配置等
        try {
            // TODO: 实现具体的配置刷新逻辑
            // 1. 解析新的配置内容
            // 2. 更新相关的配置 Bean
            // 3. 触发应用重新加载配置

            log.info("配置更新处理完成");
        } catch (Exception e) {
            log.error("处理配置更新失败", e);
        }
    }
}
