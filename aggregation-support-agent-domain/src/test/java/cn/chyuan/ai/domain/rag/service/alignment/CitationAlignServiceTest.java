package cn.chyuan.ai.domain.rag.service.alignment;

import cn.chyuan.ai.domain.rag.service.alignment.CitationAlignService.CitationAlignResult;
import cn.chyuan.ai.domain.rag.service.alignment.CitationAlignService.SentenceAlignment;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 引用溯源对齐纯函数单测（工单 0169）
 * <p>
 * 覆盖验收：中英文分句 / 空答案 / 多片段并列 / 对齐均值=citationScore / 未对齐句 / 口径一致性
 */
class CitationAlignServiceTest {

    private CitationAlignService serviceWithThreshold(double threshold) {
        CitationAlignService service = new CitationAlignService();
        ReflectionTestUtils.setField(service, "unalignedThreshold", threshold);
        return service;
    }

    @Test
    void splitsChineseAndEnglishSentences() {
        // 中英文句读混合切分
        List<String> sentences = CitationAlignService.splitSentences("你好。Hello world! 第二句；third?\n新行");

        assertThat(sentences).containsExactly("你好。", "Hello world!", "第二句；", "third?", "新行");
    }

    @Test
    void emptyAnswerYieldsZeroScore() {
        // 空答案：无句可对齐，citationScore=0
        CitationAlignResult result = serviceWithThreshold(0.1).align("", List.of("片段"));

        assertThat(result.sentences()).isEmpty();
        assertThat(result.unalignedSentences()).isEmpty();
        assertThat(result.citationScore()).isEqualTo(0.0d);
        assertThat(serviceWithThreshold(0.1).align(null, null).citationScore()).isEqualTo(0.0d);
    }

    @Test
    void tiesPickSmallestSourceIndex() {
        // 多片段并列：句子与两个片段 Jaccard 相同 → 最佳来源取索引最小者
        CitationAlignService service = serviceWithThreshold(0.1);
        String sentence = "RAG 检索";
        // 两片段 token 集相同构造并列
        List<String> sources = List.of("RAG 检索 额外", "RAG 检索 另词");

        CitationAlignResult result = service.align(sentence + "。", sources);

        assertThat(result.sentences().get(0).bestSourceIndex()).isZero();
    }

    @Test
    void citationScoreEqualsMeanOfAlignments() {
        // 对齐均值 = citationScore（核心口径断言）：
        // 句一 token 10 字，片段 13 字（+的/核/心），交集 10 → 对齐度 10/13；
        // 句二与片段无交集 → 0；均值 = (10/13)/2
        CitationAlignService service = serviceWithThreshold(0.1);
        String answer = "向量数据库是检索系统。天气真好呀。";
        List<String> sources = List.of("向量数据库是检索系统的核心。");

        CitationAlignResult result = service.align(answer, sources);

        List<SentenceAlignment> sentences = result.sentences();
        assertThat(sentences).hasSize(2);
        double mean = sentences.stream().mapToDouble(SentenceAlignment::alignment).average().orElse(0);
        assertThat(result.citationScore()).isCloseTo(mean, within(1e-9));
        assertThat(sentences.get(0).alignment()).isCloseTo(10.0 / 13, within(1e-9));
        assertThat(sentences.get(1).alignment()).isCloseTo(0.0, within(1e-9));
        assertThat(result.citationScore()).isCloseTo(10.0 / 26, within(1e-9));
    }

    @Test
    void lowAlignmentSentenceReportedAsUnaligned() {
        // 未对齐句：与片段无词面重叠 → 对齐度 0 低于阈值 → 进入未对齐列表
        CitationAlignService service = serviceWithThreshold(0.1);
        String answer = "向量检索很快。天气真好。";
        List<String> sources = List.of("向量检索通过嵌入模型实现语义匹配。");

        CitationAlignResult result = service.align(answer, sources);

        assertThat(result.unalignedSentences()).containsExactly("天气真好。");
        // 命中句：最佳来源索引 0
        assertThat(result.sentences().get(0).bestSourceIndex()).isZero();
        assertThat(result.sentences().get(0).alignment()).isGreaterThan(0.0);
        // 无片段句：索引 -1
        assertThat(result.sentences().get(1).bestSourceIndex()).isEqualTo(-1);
    }

    @Test
    void emptySourcesYieldAllUnaligned() {
        // 空片段列表：全部句零对齐、索引 -1、全部未对齐、citationScore=0
        CitationAlignService service = serviceWithThreshold(0.1);

        CitationAlignResult result = service.align("句子一。句子二。", List.of());

        assertThat(result.sentences()).hasSize(2);
        assertThat(result.unalignedSentences()).hasSize(2);
        assertThat(result.citationScore()).isEqualTo(0.0d);
    }

    @Test
    void tokenizeFollowsTraceQualityCalculatorConvention() {
        // 口径一致性：中文单字 + 英文整词 + 小写 + 忽略标点数字分隔
        Set<String> tokens = CitationAlignService.tokenize("Vector-DB，向量检索！v2");

        assertThat(tokens).containsExactlyInAnyOrder("vector", "db", "向", "量", "检", "索", "v2");
    }

    @Test
    void jaccardHandlesEmptySets() {
        assertThat(CitationAlignService.jaccard(Set.of(), Set.of("a"))).isZero();
        assertThat(CitationAlignService.jaccard(Set.of("a"), null)).isZero();
        assertThat(CitationAlignService.jaccard(Set.of("a", "b"), Set.of("b", "c"))).isCloseTo(1.0 / 3, within(1e-9));
    }
}
