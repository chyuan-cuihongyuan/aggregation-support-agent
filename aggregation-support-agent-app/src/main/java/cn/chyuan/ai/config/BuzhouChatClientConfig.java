package cn.chyuan.ai.config;

import io.github.chyuan_cuihongyuan.buzhou.core.Buzhou;
import io.github.chyuan_cuihongyuan.buzhou.core.spi.BuzhouStores;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Buzhou 增强链装配（工单 0007 决策 D1-C/D5-C + 0026 基座）
 *
 * <p>单会话长对话第二执行链：{@link Buzhou#enhance} 在 ChatClient.Builder 上注册
 * ToolCallingAdvisor + BuzhouMemoryAdvisor（memory 渐进压缩/召回由 buzhou-memory
 * 模块自动装配提供；store 由 buzhou.store.type 决定，默认 memory、生产 jdbc）。
 * ADK Runner 编排链零改动——双链分工见 issues/0007 决策 D5。
 *
 * <p>开关 {@code buzhou.chain.enhance-enabled}（默认关）：关闭时本配置完全不装配，
 * 对既有上下文零影响；guard（工单 0027）的工具闸门将挂在本链的工具调用环节。
 */
@Configuration
@ConditionalOnClass(Buzhou.class)
@ConditionalOnProperty(name = "buzhou.chain.enhance-enabled", havingValue = "true")
public class BuzhouChatClientConfig {

    /** Buzhou 增强后的 ChatClient 构建器：单会话长对话链的装配点 */
    @Bean
    @ConditionalOnBean({ChatModel.class, BuzhouStores.class})
    @ConditionalOnMissingBean(name = "buzhouChatClientBuilder")
    public ChatClient.Builder buzhouChatClientBuilder(ChatModel chatModel, BuzhouStores stores) {
        return Buzhou.enhance(ChatClient.builder(chatModel), stores);
    }
}
