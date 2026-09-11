package cn.chyuan.ai.infrastructure.config;

import cn.chyuan.ai.domain.rag.adapter.port.IQueryRewritePort;
import cn.chyuan.ai.domain.rag.service.query.RuleBasedQueryRewriter;
import cn.chyuan.ai.infrastructure.gateway.query.LlmQueryRewritePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 查询改写端口条件装配（工单 0165，W3）
 * <p>
 * 开关 {@code rag.rewrite-enabled}（默认关）：关闭时不装配任何端口，
 * 编排层 query 原样直通（零回归）。开启后按 {@code rag.rewrite-provider} 选择实现：
 * <ul>
 *   <li>rule（默认）：规则版（内置停用词表 + rag.rewrite.synonyms 同义扩展表）</li>
 *   <li>llm：LLM 适配（异常回退规则版；ChatModel 缺席时恒走规则版）</li>
 * </ul>
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "rag.rewrite-enabled", havingValue = "true")
public class QueryRewritePortConfig {

    /**
     * 规则版实现 — provider=rule（默认/缺省）时装配；
     * 同义扩展表经 rag.rewrite.synonyms 注入（格式 "key1=value1,key2=value2"）
     */
    @Bean
    @ConditionalOnProperty(name = "rag.rewrite-provider", havingValue = "rule", matchIfMissing = true)
    public IQueryRewritePort ruleBasedQueryRewriter(
            @Value("${rag.rewrite.synonyms:}") String synonymsConfig) {
        Map<String, String> synonyms = parseSynonyms(synonymsConfig);
        log.info("装配查询改写规则版: 自定义同义扩展 {} 项", synonyms.size());
        return new RuleBasedQueryRewriter(null, synonyms);
    }

    /**
     * LLM 版实现 — provider=llm 时装配；ChatModel 经 ObjectProvider 可缺席
     */
    @Bean
    @ConditionalOnProperty(name = "rag.rewrite-provider", havingValue = "llm")
    public IQueryRewritePort llmQueryRewritePort(ObjectProvider<ChatModel> chatModelProvider) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        log.info("装配查询改写 LLM 版: chatModel={}", chatModel != null ? chatModel.getClass().getSimpleName() : "缺席(走规则版)");
        return new LlmQueryRewritePort(chatModel, new RuleBasedQueryRewriter());
    }

    /** 解析 "k1=v1,k2=v2" 格式的同义扩展表配置 */
    private Map<String, String> parseSynonyms(String config) {
        Map<String, String> synonyms = new LinkedHashMap<>();
        if (config == null || config.isBlank()) {
            return synonyms;
        }
        for (String pair : config.split(",")) {
            if (pair == null || pair.isBlank()) {
                continue;
            }
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && !kv[0].isBlank() && !kv[1].isBlank()) {
                synonyms.put(kv[0].trim().toLowerCase(), kv[1].trim());
            }
        }
        return synonyms;
    }
}
