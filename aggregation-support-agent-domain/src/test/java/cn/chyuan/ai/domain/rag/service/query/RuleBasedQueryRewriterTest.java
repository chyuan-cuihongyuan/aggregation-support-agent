package cn.chyuan.ai.domain.rag.service.query;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 查询改写规则版单测（工单 0165）
 * <p>
 * 覆盖验收：停用词 / 同义表 / 空串保真 / 配置注入扩展表 / 全停用词回退
 */
class RuleBasedQueryRewriterTest {

    @Test
    void removesChineseAndEnglishStopwords() {
        RuleBasedQueryRewriter rewriter = new RuleBasedQueryRewriter();

        String rewritten = rewriter.rewrite("什么是 RAG 的用途啊");

        // 停用字"的""啊"被剔除，其余按原序保留
        assertThat(rewritten).isEqualTo("什么是 rag 用途");
    }

    @Test
    void englishStopwordsFiltered() {
        RuleBasedQueryRewriter rewriter = new RuleBasedQueryRewriter();

        String rewritten = rewriter.rewrite("What is the vector database?");

        // 英文停用词 what/is/the 被剔除，关键词保留
        assertThat(rewritten).isEqualTo("vector database");
    }

    @Test
    void appliesDefaultSynonyms() {
        RuleBasedQueryRewriter rewriter = new RuleBasedQueryRewriter();

        String rewritten = rewriter.rewrite("js 框架推荐");

        // 内置同义表：js → JavaScript
        assertThat(rewritten).contains("javascript");
    }

    @Test
    void configuredSynonymExtensionApplied() {
        // 配置注入同义扩展表
        Map<String, String> extra = new HashMap<>();
        extra.put("中间件", "Middleware");
        extra.put("es", "Elasticsearch");
        RuleBasedQueryRewriter rewriter = new RuleBasedQueryRewriter(null, extra);

        String rewritten = rewriter.rewrite("es 中间件选型");

        assertThat(rewritten).contains("elasticsearch").contains("middleware");
    }

    @Test
    void configuredStopwordsMerged() {
        // 配置注入停用词（英文整词）
        RuleBasedQueryRewriter rewriter = new RuleBasedQueryRewriter(Set.of("选型"), null);

        String rewritten = rewriter.rewrite("es 选型建议");

        assertThat(rewritten).doesNotContain("选型");
    }

    @Test
    void blankAndNullPreserved() {
        RuleBasedQueryRewriter rewriter = new RuleBasedQueryRewriter();

        // 空串 / 纯空白 / null 保真
        assertThat(rewriter.rewrite("")).isEmpty();
        assertThat(rewriter.rewrite("   ")).isBlank();
        assertThat(rewriter.rewrite(null)).isNull();
    }

    @Test
    void allStopwordQueryFallsBackToOriginal() {
        RuleBasedQueryRewriter rewriter = new RuleBasedQueryRewriter();

        // 全部为停用词时保真返回原 query，避免空查询进检索
        String rewritten = rewriter.rewrite("的了");

        assertThat(rewritten).isEqualTo("的了");
    }

    @Test
    void noRewriteNeededReturnsNormalized() {
        RuleBasedQueryRewriter rewriter = new RuleBasedQueryRewriter();

        // 无停用词、无同义词的纯中文原样（仅小写归一）
        assertThat(rewriter.rewrite("向量数据库")).isEqualTo("向量数据库");
    }
}
