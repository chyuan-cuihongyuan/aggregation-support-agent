package cn.chyuan.ai.infrastructure.config.nacos;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Properties;

/**
 * Nacos 配置服务
 * 提供配置的增删改查和监听功能
 */
@Service
public class NacosConfigService {

    private static final Logger log = LoggerFactory.getLogger(NacosConfigService.class);

    private final ConfigService configService;

    public NacosConfigService(ConfigService configService) {
        this.configService = configService;
    }

    /**
     * 获取配置
     *
     * @param dataId  配置ID
     * @param group   配置分组
     * @param timeout 超时时间（毫秒）
     * @return 配置内容
     */
    public String getConfig(String dataId, String group, long timeout) {
        try {
            return configService.getConfig(dataId, group, timeout);
        } catch (NacosException e) {
            log.error("获取配置失败: dataId={}, group={}", dataId, group, e);
            throw new RuntimeException("获取配置失败", e);
        }
    }

    /**
     * 发布配置
     *
     * @param dataId  配置ID
     * @param group   配置分组
     * @param content 配置内容
     * @return 是否成功
     */
    public boolean publishConfig(String dataId, String group, String content) {
        try {
            boolean result = configService.publishConfig(dataId, group, content);
            log.info("发布配置{}: dataId={}, group={}", result ? "成功" : "失败", dataId, group);
            return result;
        } catch (NacosException e) {
            log.error("发布配置失败: dataId={}, group={}", dataId, group, e);
            throw new RuntimeException("发布配置失败", e);
        }
    }

    /**
     * 删除配置
     *
     * @param dataId 配置ID
     * @param group  配置分组
     * @return 是否成功
     */
    public boolean removeConfig(String dataId, String group) {
        try {
            boolean result = configService.removeConfig(dataId, group);
            log.info("删除配置{}: dataId={}, group={}", result ? "成功" : "失败", dataId, group);
            return result;
        } catch (NacosException e) {
            log.error("删除配置失败: dataId={}, group={}", dataId, group, e);
            throw new RuntimeException("删除配置失败", e);
        }
    }

    /**
     * 添加配置监听器
     *
     * @param dataId   配置ID
     * @param group    配置分组
     * @param listener 监听器
     */
    public void addListener(String dataId, String group, Listener listener) {
        try {
            configService.addListener(dataId, group, listener);
            log.info("添加配置监听器: dataId={}, group={}", dataId, group);
        } catch (NacosException e) {
            log.error("添加配置监听器失败: dataId={}, group={}", dataId, group, e);
            throw new RuntimeException("添加配置监听器失败", e);
        }
    }

    /**
     * 移除配置监听器
     *
     * @param dataId   配置ID
     * @param group    配置分组
     * @param listener 监听器
     */
    public void removeListener(String dataId, String group, Listener listener) {
        configService.removeListener(dataId, group, listener);
        log.info("移除配置监听器: dataId={}, group={}", dataId, group);
    }

    /**
     * 获取 ConfigService 实例（用于高级操作）
     *
     * @return ConfigService 实例
     */
    public ConfigService getConfigService() {
        return configService;
    }
}
