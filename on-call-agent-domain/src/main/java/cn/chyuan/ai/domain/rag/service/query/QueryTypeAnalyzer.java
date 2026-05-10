package cn.chyuan.ai.domain.rag.service.query;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * 查询类型分析器 — 自动识别用户问题类型，用于选择合适的检索粒度
 * <p>
 * 问题类型：
 * <ul>
 *   <li>CONCEPT（概念性）：如"什么是RAG"、"概述系统架构" → 章节级索引</li>
 *   <li>DETAIL（细节性）：如"退款需要几个工作日"、"配置参数是什么" → 句子级索引</li>
 *   <li>GENERAL（一般性）：其他问题 → 段落级索引</li>
 * </ul>
 */
@Slf4j
@Service
public class QueryTypeAnalyzer {

    /** 概念性问题模式 */
    private static final Pattern CONCEPT_PATTERN = Pattern.compile(
            "什么是|是什么|定义|概念|概述|概览|简介|介绍|原理|架构|整体|全局|基本|基础",
            Pattern.CASE_INSENSITIVE
    );

    /** 细节性问题模式 */
    private static final Pattern DETAIL_PATTERN = Pattern.compile(
            "如何|怎么|怎样|步骤|方法|命令|参数|配置|具体|详细|几个|多少|哪个|哪些|为什么|原因|区别|差异",
            Pattern.CASE_INSENSITIVE
    );

    /** 操作性问题模式 */
    private static final Pattern OPERATION_PATTERN = Pattern.compile(
            "操作|执行|运行|启动|停止|重启|部署|安装|卸载|升级|回滚|备份|恢复",
            Pattern.CASE_INSENSITIVE
    );

    /** 故障排查问题模式 */
    private static final Pattern TROUBLESHOOT_PATTERN = Pattern.compile(
            "错误|异常|失败|报错|问题|故障|排查|解决|修复|不工作|无法|不能",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 分析查询类型
     *
     * @param query 用户查询
     * @return 查询类型
     */
    public QueryType analyze(String query) {
        if (query == null || query.trim().isEmpty()) {
            return QueryType.GENERAL;
        }

        // 检查是否为概念性问题
        if (CONCEPT_PATTERN.matcher(query).find()) {
            log.debug("查询类型分析: query={}, type=CONCEPT", query);
            return QueryType.CONCEPT;
        }

        // 检查是否为细节性问题
        if (DETAIL_PATTERN.matcher(query).find()) {
            log.debug("查询类型分析: query={}, type=DETAIL", query);
            return QueryType.DETAIL;
        }

        // 检查是否为操作性问题
        if (OPERATION_PATTERN.matcher(query).find()) {
            log.debug("查询类型分析: query={}, type=OPERATION", query);
            return QueryType.DETAIL; // 操作性问题也需要细节
        }

        // 检查是否为故障排查问题
        if (TROUBLESHOOT_PATTERN.matcher(query).find()) {
            log.debug("查询类型分析: query={}, type=TROUBLESHOOT", query);
            return QueryType.DETAIL; // 故障排查也需要细节
        }

        // 默认为一般性问题
        log.debug("查询类型分析: query={}, type=GENERAL", query);
        return QueryType.GENERAL;
    }

    /**
     * 获取对应的索引粒度
     *
     * @param queryType 查询类型
     * @return 索引粒度
     */
    public String getGranularity(QueryType queryType) {
        return switch (queryType) {
            case CONCEPT -> "section";
            case DETAIL -> "sentence";
            case GENERAL -> "paragraph";
        };
    }

    /**
     * 查询类型枚举
     */
    public enum QueryType {
        /** 概念性问题 → 章节级索引 */
        CONCEPT,
        /** 细节性问题 → 句子级索引 */
        DETAIL,
        /** 一般性问题 → 段落级索引 */
        GENERAL
    }

}
