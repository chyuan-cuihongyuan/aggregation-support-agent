package cn.chyuan.ai.infrastructure.config;

import cn.chyuan.ai.domain.rag.adapter.port.IRerankPort;
import cn.chyuan.ai.domain.rag.service.rerank.PassThroughReranker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 重排端口条件装配（工单 0164，W2）
 * <p>
 * 开关 {@code rag.rerank-provider=none|upstream}（默认 none）：
 * <ul>
 *   <li>none（默认/缺省）：装配 {@link PassThroughReranker} 规则降级（原分数原序），
 *       端口链路始终存在且零外部依赖</li>
 *   <li>upstream：装配 {@code UpstreamRerankPort}（类上条件注解，与本 Bean 条件互斥），
 *       内部异常自带原序降级</li>
 * </ul>
 * 两条件按 havingValue 互斥，任意配置组合下 IRerankPort 至多一个实现。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class RerankPortConfig {

    /**
     * 规则降级实现 — provider=none 或未配置时的默认端口实现
     */
    @Bean
    @ConditionalOnProperty(name = "rag.rerank-provider", havingValue = "none", matchIfMissing = true)
    public IRerankPort passThroughReranker() {
        log.info("装配重排端口规则降级实现: rag.rerank-provider=none（原分数原序）");
        return new PassThroughReranker();
    }
}
